using System;
using System.Security.Cryptography;
using System.Text;

namespace AirKeyboard
{
    /// <summary>
    /// One authenticated rmauth1 session: its frame-MAC key and replay state.
    /// Every client frame after "cin ok" is frame || first 16 bytes of HMAC(SKmac, T || frame),
    /// T = 'T' on TCP and 'U' on UDP. Never log the key, nonces or proofs.
    /// </summary>
    public class AuthSession
    {
        public const byte TransportTcp = (byte)'T';
        public const byte TransportUdp = (byte)'U';
        public const int TagBytes = 16;
        public const int NonceBytes = 16;

        private static readonly byte[] LabelClient = Encoding.ASCII.GetBytes("rmauth1 client");
        private static readonly byte[] LabelServer = Encoding.ASCII.GetBytes("rmauth1 server");
        private static readonly byte[] LabelMac = Encoding.ASCII.GetBytes("rmauth1 mac");

        private const int UdpWindow = 64;

        private readonly object _lock = new();
        private readonly byte[] _skMac;
        private bool _zeroed;
        private long _udpHighest = -1;
        private ulong _udpSeen;      // bit i set = id (_udpHighest - i) already accepted
        private long _tcpLast = -1;

        /// <summary>Takes ownership of skMac (from ComputeProofs); Zero() wipes it.</summary>
        public AuthSession(byte[] skMac)
        {
            _skMac = skMac;
        }

        /// <summary>
        /// n = nonceS || nonceC; proofC = HMAC(K, "rmauth1 client" || n),
        /// proofS = HMAC(K, "rmauth1 server" || n), SKmac = HMAC(K, "rmauth1 mac" || n).
        /// Compare proofC with CryptographicOperations.FixedTimeEquals.
        /// </summary>
        public static (byte[] ProofC, byte[] ProofS, byte[] SkMac) ComputeProofs(
            byte[] key, byte[] nonceS, byte[] nonceC)
        {
            return (Hmac(key, LabelClient, nonceS, nonceC),
                    Hmac(key, LabelServer, nonceS, nonceC),
                    Hmac(key, LabelMac, nonceS, nonceC));
        }

        private static byte[] Hmac(byte[] key, byte[] label, byte[] nonceS, byte[] nonceC)
        {
            using var h = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, key);
            h.AppendData(label);
            h.AppendData(nonceS);
            h.AppendData(nonceC);
            return h.GetHashAndReset();
        }

        /// <summary>True when tag is the 16-byte HMAC(SKmac, transport || frame) prefix.
        /// frame is the exact bytes received before the tag (38-byte header + payload).</summary>
        public bool VerifyTag(byte transport, ReadOnlySpan<byte> frame, ReadOnlySpan<byte> tag)
        {
            if (tag.Length != TagBytes) return false;
            Span<byte> mac = stackalloc byte[32];
            lock (_lock)
            {
                // A zeroed key would let anyone tag frames with the all-zero key
                if (_zeroed) return false;
                using var h = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, _skMac);
                h.AppendData(new[] { transport });
                h.AppendData(frame);
                h.GetHashAndReset(mac);
            }
            return CryptographicOperations.FixedTimeEquals(mac.Slice(0, TagBytes), tag);
        }

        /// <summary>
        /// UDP replay check (64-wide sliding window, as in IPsec): accepts an id above the
        /// highest seen, or one up to 63 below it that hasn't been seen. Call only after
        /// VerifyTag succeeded, so forged frames can't move the window.
        /// </summary>
        public bool AcceptUdpId(int chunkId)
        {
            lock (_lock)
            {
                long id = chunkId;
                if (id > _udpHighest)
                {
                    long shift = id - _udpHighest;
                    // C# masks shift counts to 6 bits, so a jump of 64+ must clear explicitly
                    _udpSeen = shift >= UdpWindow ? 0 : _udpSeen << (int)shift;
                    _udpSeen |= 1;
                    _udpHighest = id;
                    return true;
                }
                long back = _udpHighest - id;
                if (back >= UdpWindow) return false;
                ulong bit = 1UL << (int)back;
                if ((_udpSeen & bit) != 0) return false;
                _udpSeen |= bit;
                return true;
            }
        }

        /// <summary>TCP replay check: ids must be strictly increasing. Call after VerifyTag.</summary>
        public bool AcceptTcpId(int chunkId)
        {
            lock (_lock)
            {
                if (chunkId <= _tcpLast) return false;
                _tcpLast = chunkId;
                return true;
            }
        }

        /// <summary>Wipes SKmac; VerifyTag fails from then on.</summary>
        public void Zero()
        {
            lock (_lock)
            {
                CryptographicOperations.ZeroMemory(_skMac);
                _zeroed = true;
            }
        }
    }
}
