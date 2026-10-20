using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace AirKeyboard
{
    /// <summary>
    /// The server's password setting, kept in config.json as a PBKDF2 key (never the password).
    /// Format: {"password":{"v":1,"salt":"b64 16","iterations":100000,"key":"b64 32"}};
    /// a missing file or "password": null means no password. A file that exists but can't be
    /// read as exactly that makes Load() throw, so the server never falls back to no password.
    /// </summary>
    public class AuthConfig
    {
        // One constant so renaming the app's folder is one line
        public const string AppFolderName = "AirKeyboard";
        public const int MinPasswordChars = 8;
        public const int MaxPasswordChars = 128;
        public const int SaltBytes = 16;
        public const int KeyBytes = 32;
        public const int DefaultIterations = 100_000;
        public const int MaxIterations = 1_000_000;

        /// <summary>%APPDATA%\AirKeyboard, the default config folder (also holds the tray's flags)</summary>
        public static string AppDataFolder =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), AppFolderName);

        /// <summary>AIRKEYBOARD_CONFIG (full path) or %APPDATA%\AirKeyboard\config.json</summary>
        public static string FilePath
        {
            get
            {
                string? overridePath = Environment.GetEnvironmentVariable("AIRKEYBOARD_CONFIG");
                if (!string.IsNullOrEmpty(overridePath)) return overridePath;
                return Path.Combine(AppDataFolder, "config.json");
            }
        }

        public bool HasPassword => Key != null;
        public byte[]? Salt { get; private set; }
        public int Iterations { get; private set; }
        /// <summary>K = PBKDF2-HMAC-SHA256(UTF-8(NFC(password)), Salt, Iterations, 32)</summary>
        public byte[]? Key { get; private set; }

        /// <summary>Reads FilePath. Throws InvalidDataException (or an IO exception) when the file
        /// exists but is unreadable or malformed; the message never contains the key.</summary>
        public static AuthConfig Load()
        {
            string path = FilePath;
            var config = new AuthConfig();

            // Only "not there" means no password; File.Exists would also say false when access
            // is denied, so read and let every other error propagate
            byte[] bytes;
            try
            {
                bytes = File.ReadAllBytes(path);
            }
            catch (Exception ex) when (ex is FileNotFoundException || ex is DirectoryNotFoundException)
            {
                return config;
            }

            try
            {
                // Tolerate the UTF-8 BOM a hand edit in Notepad may add
                int start = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF ? 3 : 0;
                using JsonDocument doc = JsonDocument.Parse(bytes.AsMemory(start));
                JsonElement root = doc.RootElement;
                if (root.ValueKind != JsonValueKind.Object ||
                    !root.TryGetProperty("password", out JsonElement pw))
                {
                    throw Bad(path, "no \"password\" field");
                }
                if (pw.ValueKind == JsonValueKind.Null) return config;
                if (pw.ValueKind != JsonValueKind.Object) throw Bad(path, "\"password\" is not an object");

                if (!pw.TryGetProperty("v", out JsonElement v) || v.ValueKind != JsonValueKind.Number ||
                    !v.TryGetInt32(out int version) || version != 1)
                {
                    throw Bad(path, "unsupported version");
                }
                if (!pw.TryGetProperty("iterations", out JsonElement it) || it.ValueKind != JsonValueKind.Number ||
                    !it.TryGetInt32(out int iterations) || iterations < DefaultIterations || iterations > MaxIterations)
                {
                    throw Bad(path, "bad iterations");
                }
                byte[] salt = ReadB64(pw, "salt", SaltBytes, path);
                byte[] key = ReadB64(pw, "key", KeyBytes, path);

                config.Salt = salt;
                config.Iterations = iterations;
                config.Key = key;
                return config;
            }
            catch (JsonException)
            {
                throw Bad(path, "not valid JSON");
            }
            finally
            {
                CryptographicOperations.ZeroMemory(bytes);
            }
        }

        private static byte[] ReadB64(JsonElement obj, string name, int length, string path)
        {
            if (!obj.TryGetProperty(name, out JsonElement e) || e.ValueKind != JsonValueKind.String)
            {
                throw Bad(path, $"missing \"{name}\"");
            }
            byte[] value;
            try
            {
                value = Convert.FromBase64String(e.GetString()!);
            }
            catch (FormatException)
            {
                throw Bad(path, $"\"{name}\" is not base64");
            }
            if (value.Length != length) throw Bad(path, $"\"{name}\" must be {length} bytes");
            return value;
        }

        private static InvalidDataException Bad(string path, string reason) =>
            new($"Config file {path} is invalid ({reason}). Fix it, or run --set-password or --clear-password.");

        /// <summary>Writes FilePath atomically (temp file in the same folder, then move over).</summary>
        public void Save()
        {
            string path = FilePath;
            string? dir = Path.GetDirectoryName(Path.GetFullPath(path));
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

            byte[] json;
            using (var ms = new MemoryStream())
            {
                using (var w = new Utf8JsonWriter(ms, new JsonWriterOptions { Indented = true }))
                {
                    w.WriteStartObject();
                    if (Key == null)
                    {
                        w.WriteNull("password");
                    }
                    else
                    {
                        w.WriteStartObject("password");
                        w.WriteNumber("v", 1);
                        w.WriteString("salt", Convert.ToBase64String(Salt!));
                        w.WriteNumber("iterations", Iterations);
                        w.WriteString("key", Convert.ToBase64String(Key));
                        w.WriteEndObject();
                    }
                    w.WriteEndObject();
                }
                json = ms.ToArray();
            }

            string tmp = path + ".tmp";
            try
            {
                File.WriteAllBytes(tmp, json);
                File.Move(tmp, path, overwrite: true);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(json);
                if (File.Exists(tmp)) File.Delete(tmp);
            }
        }

        /// <summary>
        /// Sets a new password with a fresh salt (call Save() to keep it). Trailing CR/LF are
        /// stripped and the text is NFC-normalised; it must then be 8 to 128 characters, otherwise
        /// ArgumentException (whose message never contains the password).
        /// </summary>
        public void SetPassword(string password)
        {
            string pw = password.TrimEnd('\r', '\n').Normalize(NormalizationForm.FormC);
            int chars = pw.EnumerateRunes().Count();
            if (chars < MinPasswordChars || chars > MaxPasswordChars)
            {
                throw new ArgumentException(
                    $"The password must be {MinPasswordChars} to {MaxPasswordChars} characters.");
            }

            byte[] pwBytes = Encoding.UTF8.GetBytes(pw);
            try
            {
                byte[] salt = RandomNumberGenerator.GetBytes(SaltBytes);
                byte[] key = Rfc2898DeriveBytes.Pbkdf2(pwBytes, salt, DefaultIterations, HashAlgorithmName.SHA256, KeyBytes);
                Clear();
                Salt = salt;
                Iterations = DefaultIterations;
                Key = key;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(pwBytes);
            }
        }

        /// <summary>Removes the password (call Save() to write "password": null).</summary>
        public void Clear()
        {
            if (Key != null) CryptographicOperations.ZeroMemory(Key);
            Key = null;
            Salt = null;
            Iterations = 0;
        }
    }
}
