using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirKeyboard
{
    /// <summary>
    /// AirKeyboard TCP server (port 1978)
    /// Accepts chunk-encrypted commands from remote clients
    /// Protocol: Binary chunk format with XOR encryption (version 530)
    /// </summary>
    public class RemoteControlServer
    {
        /// <summary>Open TCP connections (for the tray's tooltip)</summary>
        public int ClientCount { get { lock (_clientLock) return _clients.Count; } }
        /// <summary>Raised, on any thread, after a connection is added or removed</summary>
        public event Action? ClientsChanged;
        /// <summary>
        /// Raised (with the error message) when the discovery listener can't bind UDP 2008, e.g.
        /// another program holds it. The server keeps running; only the app's auto-discovery
        /// can't find this PC. Raised inside StartAsync, before it returns: subscribe first.
        /// </summary>
        public event Action<string>? DiscoveryUnavailable;

        private const int TCP_PORT = 1978;
        private const int MAX_CLIENTS = 32;
        private const int MAX_CLIENTS_PER_ADDRESS = 4;
        // Password mode: connections that have not logged in yet share a pool of their own, so
        // they never take a logged-in session's slot; when it is full one of them is closed
        // (see AdmitWithPassword)
        private const int MAX_PREAUTH = 8;
        private const int MAX_PREAUTH_PER_ADDRESS = 2;
        private const int UDP_MIN_FRAME = 38 + 6; // header + payload prefix
        private const int UDP_DROP_LOG_MS = 10_000;
        private const int MAX_UDP_DROP_ENTRIES = 256;
        private const int DUPLICATE_WINDOW_MS = 2000;
        private const int MAX_RECENT_DATAGRAMS = 1024;
        private TcpListener? _listener;
        private bool _running = false;
        private List<ClientHandler> _clients = new();
        private readonly object _clientLock = new();
        private ActivityLogger _logger;
        private DiscoveryListener? _discoveryListener;
        private UdpClient? _commandUdp;
        private CommandProcessor _processor;
        private string _serverName = "WindowsPC";
        private const int SERVER_VERSION = 530; // v530 = UDP chunks with interval

        // Password mode (null = no password); logged-in sessions by address, for the UDP check
        private readonly AuthConfig? _auth;
        private readonly AuthLimiter _limiter = new();
        private readonly Dictionary<IPAddress, List<AuthSession>> _sessions = new();
        private readonly object _sessionLock = new();
        // Only the UDP receive loop touches this (one datagram at a time), so it needs no lock
        private readonly Dictionary<(IPAddress peer, string reason), (int count, long nextLog)> _udpDrops = new();
        // Recent datagrams without a password, oldest first (see IsDuplicateDatagram); UDP loop only too
        private readonly HashSet<string> _recentDatagrams = new();
        private readonly Queue<(string Key, long At)> _recentOrder = new();

        public RemoteControlServer(string serverName = "WindowsPC", AuthConfig? auth = null)
        {
            _logger = new ActivityLogger();
            _processor = new CommandProcessor(_logger);
            _auth = auth != null && auth.HasPassword ? auth : null;

            // Discovery and handshake carry the name behind a 3-digit byte length, and the
            // handshake appends " pwd @rmauth1 530" (the longer suffix, so the name is the same
            // in both modes), so the name must leave room for that within 999
            int maxNameBytes = 999 - Encoding.UTF8.GetByteCount($" pwd @rmauth1 {SERVER_VERSION}");
            _serverName = TruncateUtf8(serverName, maxNameBytes);
            if (_serverName.Length != serverName.Length)
            {
                _logger.Log($"Server name truncated to {maxNameBytes} UTF-8 bytes");
            }
        }

        /// <summary>Cuts s to at most maxBytes of UTF-8, never splitting a code point</summary>
        private static string TruncateUtf8(string s, int maxBytes)
        {
            int bytes = 0, chars = 0;
            foreach (Rune rune in s.EnumerateRunes())
            {
                if (bytes + rune.Utf8SequenceLength > maxBytes) break;
                bytes += rune.Utf8SequenceLength;
                chars += rune.Utf16SequenceLength;
            }
            return s.Substring(0, chars);
        }

        public async Task StartAsync()
        {
            _listener = new TcpListener(IPAddress.Any, TCP_PORT);
            _listener.Start();
            _running = true;

            _logger.Log($"Server started on TCP port {TCP_PORT}");
            Console.WriteLine($"AirKeyboard listening on port {TCP_PORT}");
            Console.WriteLine($"Server version: {SERVER_VERSION}");
            Console.WriteLine($"Server name: {_serverName}");
            _logger.Log($"Password: {(_auth != null ? "on" : "off")}");

            // Start discovery listener (UDP port 2008)
            _discoveryListener = new DiscoveryListener(_serverName, _logger);
            _discoveryListener.BindFailed += reason => DiscoveryUnavailable?.Invoke(reason);
            _ = _discoveryListener.StartAsync();

            // Commands from ver 500+ clients arrive as chunk datagrams on UDP 1978
            _commandUdp = new UdpClient(TCP_PORT);
            _ = ReceiveUdpCommandsAsync(_commandUdp);
            _logger.Log($"Command listener started on UDP port {TCP_PORT}");

            // Accept client connections
            while (_running)
            {
                try
                {
                    TcpClient client = await _listener.AcceptTcpClientAsync();
                    ClientHandler clientHandler;

                    lock (_clientLock)
                    {
                        if (_auth != null)
                        {
                            if (!AdmitWithPassword(client)) continue;
                        }
                        else
                        {
                            // The app's TCP connection is idle by design (commands go over UDP), so idle
                            // connections can't be timed out. Instead one address may hold at most 4, and
                            // a new one replaces that address's oldest: a phone reconnecting after a Wi-Fi
                            // blip is never locked out, and one host can't fill all 32 slots. A loopback
                            // newcomer is exempt (never replaces anyone, refused at the cap): the local
                            // test tools open many at once, and local code needs no network trick to send
                            // input. A LAN newcomer can still take a slot from loopback connections.
                            IPAddress address = ClientHandler.AddressOf(client);
                            bool madeRoom = false;
                            if (!IPAddress.IsLoopback(address))
                            {
                                var sameAddress = _clients.Where(c => c.IsConnected && c.PeerAddress.Equals(address)).ToList();
                                if (sameAddress.Count >= MAX_CLIENTS_PER_ADDRESS)
                                {
                                    _logger.Log($"Closing oldest connection from {address} (limit {MAX_CLIENTS_PER_ADDRESS} per address)");
                                    sameAddress[0].Disconnect(); // its thread removes it from _clients
                                    madeRoom = true;
                                }
                                else if (_clients.Count >= MAX_CLIENTS)
                                {
                                    // At the global cap, take a slot from the address holding the most
                                    // connections, so a few hosts (or one host with several IP aliases)
                                    // can't lock out a new address. Only 32 distinct addresses can.
                                    var busiest = _clients.Where(c => c.IsConnected)
                                        .GroupBy(c => c.PeerAddress)
                                        .OrderByDescending(g => g.Count())
                                        .FirstOrDefault();
                                    if (busiest != null && busiest.Count() > 1)
                                    {
                                        _logger.LogLimited("slot reclaimed", $"Closing oldest connection from {busiest.Key} (limit {MAX_CLIENTS} reached) to admit {address}");
                                        busiest.First().Disconnect(); // its thread removes it from _clients
                                        madeRoom = true;
                                    }
                                }
                            }

                            if (!madeRoom && _clients.Count >= MAX_CLIENTS)
                            {
                                _logger.LogLimited("client refused", $"Client refused (limit {MAX_CLIENTS} reached): {client.Client.RemoteEndPoint}");
                                client.Close();
                                continue;
                            }
                        }

                        EnableKeepAlive(client);
                        // Live typing sends one small frame per keystroke on this socket; no Nagle delay
                        client.NoDelay = true;
                        clientHandler = new ClientHandler(client, _logger, _processor, _serverName, SERVER_VERSION, this, _auth, _limiter);
                        _clients.Add(clientHandler);
                    }
                    ClientsChanged?.Invoke();

                    Console.WriteLine($"Client connected from {client.Client.RemoteEndPoint}");
                    _logger.Log($"Client connected: {client.Client.RemoteEndPoint}");

                    // HandleClient reads synchronously, so each client gets its own thread: it must
                    // not block this accept loop, and must not tie up a thread-pool thread for hours.
                    var thread = new Thread(() =>
                    {
                        clientHandler.HandleClient();
                        // Only a session that got going owns buttons: a password probe or a failed
                        // login from the tablet's address must not end its drag
                        if (clientHandler.Established)
                        {
                            _processor.ReleaseHeldBy(clientHandler.PeerAddress, "session ended");
                        }
                        lock (_clientLock)
                        {
                            _clients.Remove(clientHandler);
                        }
                        ClientsChanged?.Invoke();
                    })
                    {
                        IsBackground = true,
                        Name = $"client {client.Client.RemoteEndPoint}"
                    };
                    thread.Start();
                }
                catch (ObjectDisposedException)
                {
                    // Server stopped
                    break;
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error accepting client: {ex.Message}");
                    _logger.Log($"Error accepting client: {ex.Message}");
                }
            }
        }

        /// <summary>
        /// Password mode, under _clientLock: logged-in sessions keep their MAX_CLIENTS slots, and
        /// a newcomer joins the pool of connections not logged in yet. When the newcomer's address
        /// already has its share, that address's oldest waiting connection is closed. When the
        /// whole pool is full, the oldest waiting connection of the address holding the most is
        /// closed (ties: the one whose oldest is oldest), never simply the oldest overall: a host
        /// flooding from a few addresses, reconnecting each closed connection at once, would
        /// otherwise rotate the tablet's single waiting login out before it is sent.
        /// False if the newcomer was refused.
        /// </summary>
        private bool AdmitWithPassword(TcpClient client)
        {
            IPAddress address = ClientHandler.AddressOf(client);
            if (_clients.Count(c => c.IsConnected && c.Established) >= MAX_CLIENTS)
            {
                _logger.LogLimited("client refused", $"Client refused (limit {MAX_CLIENTS} reached): {client.Client.RemoteEndPoint}");
                client.Close();
                return false;
            }

            // In accept order, so each address's first element is its oldest
            var waiting = _clients.Where(c => c.IsConnected && !c.Established).ToList();
            var sameAddress = waiting.Where(c => c.PeerAddress.Equals(address)).ToList();
            ClientHandler? oldest = null;
            if (sameAddress.Count >= MAX_PREAUTH_PER_ADDRESS)
            {
                oldest = sameAddress[0];
            }
            else if (waiting.Count >= MAX_PREAUTH)
            {
                // GroupBy keeps the order of each address's first (oldest) member and OrderBy is a
                // stable sort, so among equally busy addresses the one with the oldest wins
                oldest = waiting.GroupBy(c => c.PeerAddress)
                    .OrderByDescending(g => g.Count())
                    .First()
                    .First();
            }
            if (oldest != null)
            {
                _logger.LogLimited("preauth reclaimed", $"Closing oldest connection not logged in, from {oldest.PeerAddress}, to admit {address}");
                oldest.Disconnect(); // its thread removes it from _clients
            }
            return true;
        }

        /// <summary>
        /// A login succeeded: registers the session for the UDP check and marks the handler
        /// established. False if the handler was closed meanwhile (e.g. replaced), so nothing
        /// is registered for a connection that is gone.
        /// </summary>
        internal bool Establish(ClientHandler handler, AuthSession session)
        {
            // _clientLock too, so the pool above never closes a connection that just logged in
            lock (_clientLock)
            lock (_sessionLock)
            {
                if (!handler.IsConnected) return false;
                if (!_sessions.TryGetValue(handler.PeerAddress, out var list))
                {
                    list = new List<AuthSession>();
                    _sessions[handler.PeerAddress] = list;
                }
                list.Add(session);
                handler.Session = session;
                handler.Established = true;
                return true;
            }
        }

        /// <summary>Unregisters and wipes the handler's session, if it had one</summary>
        internal void EndSession(ClientHandler handler)
        {
            lock (_sessionLock)
            {
                AuthSession? session = handler.Session;
                if (session == null) return;
                if (_sessions.TryGetValue(handler.PeerAddress, out var list) && list.Remove(session) && list.Count == 0)
                {
                    _sessions.Remove(handler.PeerAddress);
                }
                // Under the lock, so the UDP check never sees a registered session with a wiped key
                session.Zero();
            }
        }

        /// <summary>
        /// Probe idle connections so a client that vanished (e.g. left Wi-Fi) is dropped
        /// after about 45 s instead of lingering until the server restarts
        /// </summary>
        private void EnableKeepAlive(TcpClient client)
        {
            try
            {
                client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                client.Client.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, 30);
                client.Client.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveInterval, 5);
                client.Client.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveRetryCount, 3);
            }
            catch (SocketException ex)
            {
                _logger.Log($"Could not enable TCP keepalive: {ex.Message}");
            }
        }

        /// <summary>
        /// Wi-Fi now and then delivers a datagram twice. Every frame carries a new chunk id (clients
        /// count up), so a byte-identical copy from the same address
        /// within <see cref="DUPLICATE_WINDOW_MS"/> is such a copy and must not run again (a second
        /// click, a doubled letter). Password mode drops it as a replay instead. Keyed by a hash, so
        /// a 64 KB datagram costs no more memory than a small one. Only the UDP receive loop calls
        /// this, one datagram at a time.
        /// </summary>
        private bool IsDuplicateDatagram(IPAddress peer, byte[] datagram)
        {
            long now = Environment.TickCount64;
            while (_recentOrder.Count > 0 &&
                   (now - _recentOrder.Peek().At > DUPLICATE_WINDOW_MS || _recentOrder.Count >= MAX_RECENT_DATAGRAMS))
            {
                _recentDatagrams.Remove(_recentOrder.Dequeue().Key);
            }
            string key = $"{peer}|{Convert.ToBase64String(SHA256.HashData(datagram))}";
            if (!_recentDatagrams.Add(key)) return true;
            _recentOrder.Enqueue((key, now));
            return false;
        }

        private async Task ReceiveUdpCommandsAsync(UdpClient udp)
        {
            while (_running)
            {
                try
                {
                    var result = await udp.ReceiveAsync();
                    IPAddress peer = result.RemoteEndPoint.Address;
                    byte[]? frame = result.Buffer;
                    if (_auth != null)
                    {
                        // Checked before the frame is decoded, so an untagged packet never moves the mouse
                        frame = CheckUdpFrame(result.Buffer, peer, out string reason);
                        if (frame == null)
                        {
                            LogUdpDrop(peer, reason);
                            continue;
                        }
                    }
                    else if (IsDuplicateDatagram(peer, frame))
                    {
                        LogUdpDrop(peer, "duplicate");
                        continue;
                    }
                    _processor.ProcessChunk(frame, $"UDP {peer}", peer);
                }
                catch (ObjectDisposedException)
                {
                    break;
                }
                catch (SocketException ex)
                {
                    // Windows reports ICMP port-unreachable on UDP sockets as ConnectionReset; ignore it
                    if (ex.SocketErrorCode != SocketError.ConnectionReset)
                    {
                        _logger.Log($"UDP command receive error: {ex.Message}");
                    }
                }
            }
        }

        /// <summary>
        /// Password mode: a datagram is frame || 16-byte tag. It counts only if a logged-in session
        /// from that address made the tag (the address alone proves nothing, it is just a cheap
        /// prefilter) and its chunk id is new. Returns the frame without the tag, or null.
        /// </summary>
        private byte[]? CheckUdpFrame(byte[] datagram, IPAddress peer, out string reason)
        {
            int frameLength = datagram.Length - AuthSession.TagBytes;
            if (frameLength < UDP_MIN_FRAME)
            {
                reason = "too short";
                return null;
            }

            var frame = new ReadOnlySpan<byte>(datagram, 0, frameLength);
            var tag = new ReadOnlySpan<byte>(datagram, frameLength, AuthSession.TagBytes);
            lock (_sessionLock)
            {
                if (!_sessions.TryGetValue(peer, out var list))
                {
                    reason = "no session";
                    return null;
                }
                foreach (AuthSession session in list)
                {
                    if (!session.VerifyTag(AuthSession.TransportUdp, frame, tag)) continue;
                    if (!session.AcceptUdpId(BitConverter.ToInt32(datagram, 5))) // chunk id: int32 LE at 5
                    {
                        reason = "replay";
                        return null;
                    }
                    reason = "";
                    return datagram[..frameLength];
                }
            }
            reason = "bad tag";
            return null;
        }

        /// <summary>
        /// At most one line per address and reason every 10 s, with a running count. Spoofed
        /// source addresses each cost an entry, so the table is capped (and starts over when full).
        /// </summary>
        private void LogUdpDrop(IPAddress peer, string reason)
        {
            long now = Environment.TickCount64;
            var key = (peer, reason);
            if (!_udpDrops.TryGetValue(key, out var state))
            {
                if (_udpDrops.Count >= MAX_UDP_DROP_ENTRIES) _udpDrops.Clear();
                state = (0, now);
            }
            state.count++;
            if (now >= state.nextLog)
            {
                _logger.LogLimited("udp dropped", $"UDP dropped from {peer}: {reason} ({state.count})");
                state.nextLog = now + UDP_DROP_LOG_MS;
            }
            _udpDrops[key] = state;
        }

        /// <summary>Lets go of every mouse button a client holds down (Ctrl+C, process exit)</summary>
        public void ReleaseHeldButtons() => _processor.ReleaseAllHeld("server stopping");

        public void Stop()
        {
            _running = false;
            ReleaseHeldButtons();

            lock (_clientLock)
            {
                foreach (var client in _clients)
                {
                    client.Disconnect();
                }
                _clients.Clear();
            }

            _discoveryListener?.Stop();
            _commandUdp?.Close();
            _listener?.Stop();
            _logger.Log("Server stopped");
            Console.WriteLine("Server stopped.");
        }
    }

    /// <summary>
    /// Handles a single client connection
    /// Implements the protocol handshake and chunk message processing.
    /// Password mode (rmauth1): the handshake also carries a challenge, the client's first frame
    /// must be a correct "ath" login within 10 s, and every frame after it carries a 16-byte tag.
    /// </summary>
    public class ClientHandler
    {
        private TcpClient _client;
        private NetworkStream _stream;
        private ActivityLogger _logger;
        private CommandProcessor _processor;
        private readonly string _endpoint;
        private readonly string _source;
        private readonly PasteTransfer _paste = new(); // this connection's "clp" state
        private readonly object _sendLock = new();
        private volatile bool _connected = true;
        private int _disconnected;
        private string _serverName;
        private int _serverVersion;
        private readonly RemoteControlServer _server;
        private readonly AuthConfig? _auth; // null = no password
        private readonly AuthLimiter _limiter;
        private byte[] _nonceS = Array.Empty<byte>();

        private const int HEADER_SIZE = 38;
        private const int PREFIX_SIZE = 6; // 3-char type + 3-digit length
        private const byte HEADER_BYTE = 0x23;
        private const int CHUNK_ID_OFFSET = 5; // int32, little-endian
        private const int PROOF_BYTES = 32;
        // Password mode: from accept to a correct login. The only read deadline: a logged-in
        // session may stay quiet on TCP for hours (all input goes over UDP until a paste).
        private const int AUTH_TIMEOUT_MS = 10_000;

        /// <summary>The peer's address, IPv4-mapped IPv6 normalized to IPv4</summary>
        public IPAddress PeerAddress { get; }

        public bool IsConnected => _connected;

        /// <summary>
        /// The session got going: the handshake was sent (no password) or the client logged in.
        /// Set by RemoteControlServer.Establish in password mode.
        /// </summary>
        public bool Established { get; internal set; }

        /// <summary>Password mode, after login: the session's tag key and replay state</summary>
        internal AuthSession? Session { get; set; }

        public static IPAddress AddressOf(TcpClient client)
        {
            IPAddress address = (client.Client.RemoteEndPoint as IPEndPoint)?.Address ?? IPAddress.None;
            return address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address;
        }

        public ClientHandler(TcpClient client, ActivityLogger logger, CommandProcessor processor, string serverName, int serverVersion,
            RemoteControlServer server, AuthConfig? auth, AuthLimiter limiter)
        {
            _client = client;
            PeerAddress = AddressOf(client);
            _stream = client.GetStream();
            _endpoint = client.Client.RemoteEndPoint?.ToString() ?? "unknown";
            _source = $"TCP {(client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "unknown"}";
            _logger = logger;
            _processor = processor;
            _serverName = serverName;
            _serverVersion = serverVersion;
            _server = server;
            _auth = auth;
            _limiter = limiter;
        }

        public void HandleClient()
        {
            System.Threading.Timer? authDeadline = null;
            try
            {
                if (_auth != null)
                {
                    authDeadline = new System.Threading.Timer(_ =>
                    {
                        if (!Established) Disconnect();
                    }, null, AUTH_TIMEOUT_MS, Timeout.Infinite);
                }

                // Send handshake
                if (!SendHandshake()) return;

                if (_auth == null)
                {
                    Established = true;
                }
                else if (!AwaitLogin())
                {
                    return;
                }

                while (_connected)
                {
                    byte[]? frame = ReadFrame(out byte[] payload);
                    if (frame == null) return;

                    AuthSession? session = Session;
                    if (session != null)
                    {
                        byte[] tag = new byte[AuthSession.TagBytes];
                        if (!ReadFully(tag, 0, tag.Length)) return;
                        bool tagOk = session.VerifyTag(AuthSession.TransportTcp, frame, tag);
                        if (!tagOk || !session.AcceptTcpId(BitConverter.ToInt32(frame, CHUNK_ID_OFFSET)))
                        {
                            // Disconnect() from another thread wipes the key; that is not the client's fault
                            if (_connected) _logger.Log($"[{_source}] {(tagOk ? "replay" : "bad tag")}, closing");
                            return;
                        }
                    }

                    _processor.ProcessPayload(payload, _source, PeerAddress, _paste, Reply);
                }
            }
            catch (Exception ex) when (ex is IOException || ex is ObjectDisposedException)
            {
                // Client disconnected, or Disconnect() closed it (e.g. replaced by a newer connection)
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error handling client: {ex.Message}");
                _logger.Log($"Error handling client: {ex.Message}");
            }
            finally
            {
                authDeadline?.Dispose();
                Disconnect();
            }
        }

        /// <summary>
        /// Reads one chunk frame: 38-byte header, then the encrypted payload "ttt" + 3-digit
        /// length + body; the length tells exactly where the frame ends. Returns the frame as
        /// received (what a tag covers) and sets payload to its decrypted payload. Null if the
        /// client closed the connection or sent something that isn't a frame.
        /// </summary>
        private byte[]? ReadFrame(out byte[] payload)
        {
            payload = Array.Empty<byte>();
            byte[] header = new byte[HEADER_SIZE];
            if (!ReadFully(header, 0, HEADER_SIZE)) return null;
            if (header[0] != HEADER_BYTE)
            {
                // No bytes in the log: they may be private text
                _logger.Log($"[{_source}] Bad frame header, closing connection");
                return null;
            }

            byte[] frameKey = EncryptionUtil.GetFrameKey(header);
            byte[] prefix = new byte[PREFIX_SIZE];
            if (!ReadFully(prefix, 0, PREFIX_SIZE)) return null;

            int length = 0;
            for (int i = 3; i < PREFIX_SIZE; i++)
            {
                int digit = prefix[i] ^ frameKey[i % 8];
                if (digit < '0' || digit > '9')
                {
                    _logger.Log($"[{_source}] Bad payload length field, closing connection");
                    return null;
                }
                length = length * 10 + (digit - '0');
            }

            byte[] frame = new byte[HEADER_SIZE + PREFIX_SIZE + length];
            header.CopyTo(frame, 0);
            prefix.CopyTo(frame, HEADER_SIZE);
            if (!ReadFully(frame, HEADER_SIZE + PREFIX_SIZE, length)) return null;

            // XOR key index counts from the payload start
            payload = new byte[PREFIX_SIZE + length];
            for (int i = 0; i < payload.Length; i++)
            {
                payload[i] = (byte)(frame[HEADER_SIZE + i] ^ frameKey[i % 8]);
            }
            return frame;
        }

        /// <summary>
        /// Password mode: the first frame must be "ath" "1 &lt;nonceC b64&gt; &lt;proofC b64&gt;",
        /// answered with "cin" "ok &lt;proofS b64&gt;", "fail" or "locked &lt;seconds&gt;". True once
        /// logged in. Nonces, proofs and keys are never logged.
        /// </summary>
        private bool AwaitLogin()
        {
            byte[]? frame = ReadFrame(out byte[] payload);
            if (frame == null) return false;

            var parsed = EncryptionUtil.ParseCommand(payload);
            if (parsed == null || parsed.Value.commandType != "ath")
            {
                // Nothing before a login is executed; not counted as a failed login
                _logger.LogLimited("not logged in", $"[{_source}] First frame is not a login, closing");
                return false;
            }

            // A locked address gets no answer to its proof, so it can't keep guessing
            if (_limiter.IsLocked(PeerAddress, out int seconds))
            {
                Send(Message("cin", $"locked {seconds}"));
                _logger.LogLimited("auth locked", $"Auth locked [{PeerAddress}] for {seconds} s");
                return false;
            }

            string[] fields = Encoding.ASCII.GetString(parsed.Value.data).Split(' ');
            byte[]? nonceC = fields.Length == 3 && fields[0] == "1" ? FromBase64(fields[1]) : null;
            byte[]? proofC = fields.Length == 3 && fields[0] == "1" ? FromBase64(fields[2]) : null;
            if (nonceC?.Length == AuthSession.NonceBytes && proofC?.Length == PROOF_BYTES)
            {
                var (expected, proofS, skMac) = AuthSession.ComputeProofs(_auth!.Key!, _nonceS, nonceC);
                bool ok = CryptographicOperations.FixedTimeEquals(expected, proofC);
                CryptographicOperations.ZeroMemory(expected);
                if (ok)
                {
                    // Registered before "cin ok", so the client's first tagged datagram finds it
                    var session = new AuthSession(skMac);
                    if (!_server.Establish(this, session))
                    {
                        session.Zero();
                        return false;
                    }
                    _limiter.Success(PeerAddress);
                    Send(Message("cin", "ok " + Convert.ToBase64String(proofS)));
                    _logger.Log($"Auth ok [{PeerAddress}]");
                    return true;
                }
                CryptographicOperations.ZeroMemory(skMac);
            }

            // A wrong proof and a malformed login both count
            int failures = _limiter.Fail(PeerAddress);
            _logger.LogLimited("auth failed", $"Auth failed [{PeerAddress}] ({failures} of {AuthLimiter.MaxFailures})");
            if (_limiter.IsLocked(PeerAddress, out seconds))
            {
                _logger.LogLimited("auth locked", $"Auth locked [{PeerAddress}] for {seconds} s");
            }
            Send(Message("cin", "fail"));
            return false;
        }

        private static byte[]? FromBase64(string s)
        {
            try
            {
                return Convert.FromBase64String(s);
            }
            catch (FormatException)
            {
                return null;
            }
        }

        private bool SendHandshake()
        {
            try
            {
                // Format: "SIN" + 3-digit length + "Windows nopwd @rm1 530". The client reads the
                // fields by splitting on spaces, so the name must be one token: "Office PC" ->
                // "Office_PC". The third field marks AirKeyboard's login ("@rm1" without a password,
                // "@rmauth1" with one); the app refuses any server that doesn't send one.
                // Password mode: "Windows pwd @rmauth1 530", then in the same write the challenge
                // "chl" + 3-digit length + "1 <salt b64> <iterations> <nonceS b64>".
                string name = string.Concat(_serverName.Select(c => char.IsWhiteSpace(c) ? '_' : c));
                string handshakeData = _auth != null
                    ? $"{name} pwd @rmauth1 {_serverVersion}"
                    : $"{name} nopwd @rm1 {_serverVersion}";
                byte[] message = Message("SIN", handshakeData);

                if (_auth != null)
                {
                    _nonceS = RandomNumberGenerator.GetBytes(AuthSession.NonceBytes);
                    string challenge = $"1 {Convert.ToBase64String(_auth.Salt!)} {_auth.Iterations} {Convert.ToBase64String(_nonceS)}";
                    message = message.Concat(Message("chl", challenge)).ToArray();
                }

                Send(message);

                Console.WriteLine($"Handshake sent: {handshakeData}");
                _logger.Log($"Handshake sent: {handshakeData}");
                return true;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error sending handshake: {ex.Message}");
                return false;
            }
        }

        /// <summary>A plain server message: 3-char marker + 3-digit UTF-8 byte length + body</summary>
        private static byte[] Message(string marker, string body) => Message(marker, Encoding.UTF8.GetBytes(body));

        private static byte[] Message(string marker, byte[] data) =>
            Encoding.ASCII.GetBytes(marker + data.Length.ToString("D3")).Concat(data).ToArray();

        /// <summary>
        /// A reply to a "sys" request (body at most 999 bytes). Not tagged in password mode: the
        /// tags authenticate what the client sends, and only the logged-in client reads this stream.
        /// </summary>
        private void Reply(string marker, byte[] body) => Send(Message(marker, body));

        private void Send(byte[] message)
        {
            // Replies are written from this connection's thread today; the lock keeps any other
            // writer from interleaving its bytes with a message
            lock (_sendLock)
            {
                _stream.Write(message, 0, message.Length);
                _stream.Flush();
            }
        }

        /// <summary>Reads exactly count bytes; false if the client closed the connection first</summary>
        private bool ReadFully(byte[] buffer, int offset, int count)
        {
            while (count > 0)
            {
                int read = _stream.Read(buffer, offset, count);
                if (read == 0) return false;
                offset += read;
                count -= read;
            }
            return true;
        }

        public void Disconnect()
        {
            // Called from both the client's thread and Stop(); only the first call does the work
            if (Interlocked.Exchange(ref _disconnected, 1) == 1) return;
            _connected = false;
            _server.EndSession(this);
            try
            {
                _client.Close();
                _stream.Dispose();
            }
            catch { }

            _logger.Log($"Client disconnected: {_endpoint}");
        }
    }
}
