using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirKeyboard
{
    /// <summary>
    /// UDP Discovery listener
    /// Listens on port 2008 for "Hello" broadcasts and responds with "BC" format
    /// </summary>
    public class DiscoveryListener
    {
        private const int DISCOVERY_PORT = 2008;
        private const string DISCOVERY_MARKER = "Hello";
        private const string RESPONSE_MARKER = "BC";

        // The app sends one Hello every 300 ms; answer each source at most every 100 ms, and log
        // it at most once a minute, so a flood of (spoofed) Hellos can't be amplified or fill the log
        private const long MIN_REPLY_INTERVAL_MS = 100;
        private const long LOG_INTERVAL_MS = 60_000;
        private const int MAX_TRACKED_SOURCES = 1024;
        private readonly Dictionary<IPAddress, long> _lastReply = new();
        private readonly Dictionary<IPAddress, long> _lastLogged = new();

        /// <summary>Raised (with the error message) when UDP 2008 can't be bound: no discovery</summary>
        public event Action<string>? BindFailed;

        private UdpClient? _udpClient;
        private bool _running = false;
        private string _serverName;
        private ActivityLogger _logger;

        public DiscoveryListener(string serverName, ActivityLogger logger)
        {
            _serverName = serverName;
            _logger = logger;
        }

        public async Task StartAsync()
        {
            try
            {
                // No EnableBroadcast: replies are unicast, and receiving broadcasts doesn't need it.
                // Without it, a Hello spoofed from a broadcast address can't make us broadcast.
                _udpClient = new UdpClient(DISCOVERY_PORT);
                _running = true;

                Console.WriteLine($"Discovery listener started on UDP port {DISCOVERY_PORT}");
                _logger.Log($"Discovery listener started on UDP port {DISCOVERY_PORT}");

                // Listen for discovery broadcasts
                while (_running)
                {
                    try
                    {
                        var result = await _udpClient.ReceiveAsync();
                        string message = Encoding.UTF8.GetString(result.Buffer);

                        if (message == DISCOVERY_MARKER && ShouldReply(result.RemoteEndPoint))
                        {
                            // Respond with server info
                            string response = BuildDiscoveryResponse(_serverName);
                            byte[] responseData = Encoding.UTF8.GetBytes(response);

                            await _udpClient.SendAsync(responseData, responseData.Length, result.RemoteEndPoint);

                            if (Due(_lastLogged, result.RemoteEndPoint.Address, LOG_INTERVAL_MS))
                            {
                                _logger.Log($"Discovery: Responded to {result.RemoteEndPoint} with '{_serverName}'");
                            }
                        }
                    }
                    catch (ObjectDisposedException)
                    {
                        break;
                    }
                    catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionReset)
                    {
                        // Windows reports ICMP port-unreachable for an earlier reply this way; ignore it
                    }
                    catch (Exception ex)
                    {
                        if (_running)
                        {
                            _logger.LogLimited("discovery error", $"Discovery receive error: {ex.Message}");
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Failed to start discovery listener: {ex.Message}");
                _logger.Log($"Failed to start discovery listener: {ex.Message}");
                BindFailed?.Invoke(ex.Message);
            }
        }

        public void Stop()
        {
            _running = false;
            try
            {
                _udpClient?.Close();
                _udpClient?.Dispose();
            }
            catch { }

            Console.WriteLine("Discovery listener stopped");
            _logger.Log("Discovery listener stopped");
        }

        /// <summary>Never reply to a broadcast, multicast or port-0 source, nor too often to one address</summary>
        private bool ShouldReply(IPEndPoint from)
        {
            IPAddress address = from.Address;
            if (from.Port == 0 || address.Equals(IPAddress.Broadcast) || address.IsIPv6Multicast) return false;
            if (address.AddressFamily == AddressFamily.InterNetwork && (address.GetAddressBytes()[0] & 0xF0) == 0xE0) return false;
            return Due(_lastReply, address, MIN_REPLY_INTERVAL_MS);
        }

        /// <summary>True (and records now) if address was last seen in times more than intervalMs ago</summary>
        private static bool Due(Dictionary<IPAddress, long> times, IPAddress address, long intervalMs)
        {
            long now = Environment.TickCount64;
            if (times.TryGetValue(address, out long last) && now - last < intervalMs) return false;
            if (times.Count >= MAX_TRACKED_SOURCES) times.Clear(); // spoofed sources can't grow it without bound
            times[address] = now;
            return true;
        }

        /// <summary>
        /// Build discovery response
        /// Format: "BC" + "003" + "MyPC"
        /// </summary>
        private string BuildDiscoveryResponse(string serverName)
        {
            // Format: "BC" + 3-digit length + server_name
            // Example: "BC" + "008" + "WindowsPC"
            // The length counts UTF-8 bytes (what the client parses), not chars; the caller
            // encodes the whole reply as UTF-8, so the name bytes follow it exactly.

            string lengthStr = Encoding.UTF8.GetByteCount(serverName).ToString("D3");
            return RESPONSE_MARKER + lengthStr + serverName;
        }
    }
}
