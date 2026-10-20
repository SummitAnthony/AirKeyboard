using System;
using System.Text;

namespace AirKeyboard
{
    /// <summary>
    /// Chunk encryption/decryption utilities
    /// Implements XOR-based encryption with 8-byte cyclic key
    /// </summary>
    public class EncryptionUtil
    {
        private const byte HEADER_BYTE = 0x23; // '#'
        private const byte KEY_XOR_CONSTANT = 0x35; // Encryption key obfuscation
        private const int CHUNK_HEADER_SIZE = 38; // 38-byte header for UDP chunks

        /// <summary>
        /// Decrypt a chunk message (server reception - decrypt client's UDP message)
        /// </summary>
        public static byte[]? DecryptChunk(byte[] data)
        {
            try
            {
                // No console output here: anyone on the LAN can send junk, and the caller logs drops
                if (data == null || data.Length < CHUNK_HEADER_SIZE)
                {
                    return null;
                }

                // 1. Verify header byte
                if (data[0] != HEADER_BYTE)
                {
                    return null;
                }

                // 2. Extract key byte at offset 29, XOR with 0x35
                byte keyByteEncrypted = data[29];
                byte keyByte = (byte)(keyByteEncrypted ^ KEY_XOR_CONSTANT);

                // 3. Extract frame key from offset 30-37 (8 bytes)
                byte[] frameKeyEncrypted = new byte[8];
                Array.Copy(data, 30, frameKeyEncrypted, 0, 8);

                // 4. XOR frame key with key byte to get actual decryption key
                byte[] frameKey = new byte[8];
                for (int i = 0; i < 8; i++)
                {
                    frameKey[i] = (byte)(frameKeyEncrypted[i] ^ keyByte);
                }

                // 5. The timestamp at offset 13-20 is not checked: client clocks drift

                // 6. Extract payload from offset 38 onward
                if (data.Length < CHUNK_HEADER_SIZE)
                {
                    return new byte[0]; // Empty payload
                }

                byte[] encryptedPayload = new byte[data.Length - CHUNK_HEADER_SIZE];
                Array.Copy(data, CHUNK_HEADER_SIZE, encryptedPayload, 0, encryptedPayload.Length);

                // 7. XOR payload cyclic with frame key
                byte[] decryptedPayload = new byte[encryptedPayload.Length];
                for (int i = 0; i < encryptedPayload.Length; i++)
                {
                    decryptedPayload[i] = (byte)(encryptedPayload[i] ^ frameKey[i % 8]);
                }

                return decryptedPayload;
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>
        /// Derive the 8-byte payload key from a 38-byte chunk header (offsets 29 and 30-37)
        /// </summary>
        public static byte[] GetFrameKey(byte[] header)
        {
            byte keyByte = (byte)(header[29] ^ KEY_XOR_CONSTANT);
            byte[] frameKey = new byte[8];
            for (int i = 0; i < 8; i++)
            {
                frameKey[i] = (byte)(header[30 + i] ^ keyByte);
            }
            return frameKey;
        }

        /// <summary>
        /// Encrypt a payload into chunk format (server sending to client)
        /// </summary>
        public static byte[] EncryptChunk(byte[] payload, int chunkId = 0)
        {
            try
            {
                byte[] frame = new byte[CHUNK_HEADER_SIZE + payload.Length];

                // Generate random keys
                Random rand = new Random();
                byte keyByte = (byte)rand.Next(1, 256);
                byte[] frameKey = new byte[8];
                for (int i = 0; i < 8; i++)
                {
                    frameKey[i] = (byte)rand.Next(1, 256);
                }

                // 1. Header byte
                frame[0] = HEADER_BYTE;

                // 2. Version (3.0f, little-endian float)
                float version = 3.0f;
                Array.Copy(BitConverter.GetBytes(version), 0, frame, 1, 4);

                // 3. Chunk ID
                Array.Copy(BitConverter.GetBytes(chunkId), 0, frame, 5, 4);

                // 4. Chunk interval (0.0f initially)
                Array.Copy(BitConverter.GetBytes(0.0f), 0, frame, 9, 4);

                // 5. Timestamp (XOR with key byte)
                long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                byte[] timestampBytes = BitConverter.GetBytes((double)timestamp);
                for (int i = 0; i < 8; i++)
                {
                    timestampBytes[i] = (byte)(timestampBytes[i] ^ keyByte);
                }
                Array.Copy(timestampBytes, 0, frame, 13, 8);

                // 6. Two random filler ints
                Array.Copy(BitConverter.GetBytes(rand.Next()), 0, frame, 21, 4);
                Array.Copy(BitConverter.GetBytes(rand.Next()), 0, frame, 25, 4);

                // 7. Key byte (XOR with 0x35)
                frame[29] = (byte)(keyByte ^ KEY_XOR_CONSTANT);

                // 8. Frame key (XOR with key byte)
                for (int i = 0; i < 8; i++)
                {
                    frame[30 + i] = (byte)(frameKey[i] ^ keyByte);
                }

                // 9. Encrypted payload (XOR cyclic with frame key)
                for (int i = 0; i < payload.Length; i++)
                {
                    frame[CHUNK_HEADER_SIZE + i] = (byte)(payload[i] ^ frameKey[i % 8]);
                }

                return frame;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error encrypting chunk: {ex.Message}");
                return new byte[0];
            }
        }

        /// <summary>
        /// Parse a command payload (format: "key123..." or "mos123...")
        /// </summary>
        public static (string commandType, int length, byte[] data)? ParseCommand(byte[] payload)
        {
            try
            {
                if (payload.Length < 6)
                {
                    return null; // Too short
                }

                // Extract 3-byte command type
                string commandType = Encoding.ASCII.GetString(payload, 0, 3);

                // Extract 3-byte length (ASCII decimal)
                string lengthStr = Encoding.ASCII.GetString(payload, 3, 3).Trim();
                if (!int.TryParse(lengthStr, out int length))
                {
                    return null;
                }

                // Extract data
                if (payload.Length < 6 + length)
                {
                    return null;
                }

                byte[] data = new byte[length];
                Array.Copy(payload, 6, data, 0, length);

                return (commandType, length, data);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Create a command payload (format: "key123..." or "mos123...")
        /// </summary>
        public static byte[] CreateCommand(string commandType, byte[] data)
        {
            byte[] result = new byte[6 + data.Length];

            // 3-byte command type
            byte[] typeBytes = Encoding.ASCII.GetBytes(commandType.PadRight(3));
            Array.Copy(typeBytes, 0, result, 0, 3);

            // 3-byte length (ASCII decimal, right-padded)
            string lengthStr = data.Length.ToString("D3");
            byte[] lengthBytes = Encoding.ASCII.GetBytes(lengthStr);
            Array.Copy(lengthBytes, 0, result, 3, 3);

            // Data
            Array.Copy(data, 0, result, 6, data.Length);

            return result;
        }
    }
}
