using System;
using System.IO;

namespace AirKeyboard
{
    /// <summary>
    /// Per-TCP-connection state of a "clp" (Paste on PC) transfer.
    /// Collects raw UTF-8 bytes between "b" and "e"; decoding happens only at the end,
    /// so a slice that splits a code point still works.
    /// </summary>
    public class PasteTransfer
    {
        public const int MAX_BYTES = 1024 * 1024; // 1 MiB

        private MemoryStream? _buffer; // null = no active transfer
        private bool _discarded;       // too large: ignore "d"/"e" until the next "b"

        public void Begin()
        {
            _buffer = new MemoryStream();
            _discarded = false;
        }

        /// <summary>Appends a slice; returns an error to log, or null</summary>
        public string? Append(byte[] body, int offset, int count)
        {
            if (_discarded) return null;
            if (_buffer == null) return "clp data ignored: no active transfer";

            if (_buffer.Length + count > MAX_BYTES)
            {
                _buffer = null;
                _discarded = true;
                return $"clp transfer discarded: larger than {MAX_BYTES} bytes";
            }

            _buffer.Write(body, offset, count);
            return null;
        }

        /// <summary>Ends the transfer; returns the collected bytes, or null with an error to log</summary>
        public byte[]? End(out string? error)
        {
            byte[]? bytes = _buffer?.ToArray();
            error = bytes != null ? null
                : _discarded ? "clp end ignored: transfer was discarded"
                : "clp end ignored: no active transfer";
            _buffer = null;
            _discarded = false;
            return bytes;
        }
    }
}
