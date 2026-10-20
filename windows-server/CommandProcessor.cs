using System;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace AirKeyboard
{
    /// <summary>
    /// Decrypts chunk frames and executes the commands inside them.
    ///
    /// Payload = 3-char type + 3-digit length + command:
    ///   mos  "m dx dy"  relative move      key  "t<text>"        type text
    ///        "c b"      click (1 L,2 R,3 M)     "[NAME]"         press key
    ///        "d b"      double click            "[CTRL+SHIFT+T]" key combo
    ///        "p b"/"r b" button down/up
    ///        "s n"      scroll n notches (+ up)
    ///        "z n"      zoom n notches (Ctrl + wheel, + in; our app's pinch)
    ///   clp  "b" begin, "d<bytes>" append UTF-8 bytes, "e" end: put the text on the
    ///        clipboard and press Ctrl+V. TCP session only (see PasteTransfer); ignored over UDP.
    ///   sys  "pwr shutdown|restart|logoff|sleep|lock", "url <http(s) URL>", "win list",
    ///        "win focus <id>", "win close <id>", "clp get", "png". TCP session only; ignored over UDP.
    ///        Replies go back on that session as plain server messages (3-char marker + 3-digit
    ///        byte length + body): "wnl" "<id>\t<title>" per window then "wne"; "cbb", "cbd"
    ///        slices of the PC clipboard text, "cbe" (body "toolarge" over 1 MiB); "png" "" for
    ///        the heartbeat "png", which is never logged. See SystemCommands.
    /// mos and key frames are accepted on UDP and on the TCP session (live typing sends key
    /// frames over TCP). A "key t" frame that types fine is not logged at all.
    /// peer is the sender's address: it owns the buttons it holds down (see HeldButtons).
    /// </summary>
    public class CommandProcessor
    {
        private readonly InputController _input = new();
        private readonly ActivityLogger _logger;
        private readonly object _pasteLock = new();
        private static readonly UTF8Encoding StrictUtf8 = new(false, true);
        private readonly HeldButtons _held;
        private readonly SystemCommands _system;

        public CommandProcessor(ActivityLogger logger)
        {
            _logger = logger;
            _held = new HeldButtons(_input, logger);
            _system = new SystemCommands(_input, logger);
        }

        /// <summary>Releases the buttons peer holds down (its session ended)</summary>
        public void ReleaseHeldBy(IPAddress peer, string reason) => _held.ReleaseOwnedBy(peer, reason);

        public void ReleaseAllHeld(string reason) => _held.ReleaseAll(reason);

        public void ProcessChunk(byte[] frame, string source, IPAddress peer)
        {
            byte[]? payload = EncryptionUtil.DecryptChunk(frame);
            if (payload == null || payload.Length == 0)
            {
                _logger.LogLimited("dropped frame", $"[{source}] Dropped frame: decrypt failed ({frame.Length} bytes)");
                return;
            }

            ProcessPayload(payload, source, peer, null);
        }

        /// <summary>
        /// Executes a decrypted payload. paste is the connection's clipboard transfer state and
        /// reply sends a server message (marker, body) on it; null means the payload came over UDP,
        /// where "clp" and "sys" are not accepted.
        /// </summary>
        public void ProcessPayload(byte[] payload, string source, IPAddress peer, PasteTransfer? paste,
            Action<string, byte[]>? reply = null)
        {
            var parsed = EncryptionUtil.ParseCommand(payload);
            if (parsed == null)
            {
                // A malformed "clp" frame still carries clipboard text, so never echo its bytes
                if (payload.Length >= 3 && payload[0] == 'c' && payload[1] == 'l' && payload[2] == 'p')
                {
                    _logger.LogLimited("clp dropped", paste == null
                        ? $"[{source}] clp over UDP ignored"
                        : $"[{source}] clp dropped: bad payload ({payload.Length} bytes)");
                    return;
                }

                // Never echo the bytes: a malformed "key" frame still carries typed text
                _logger.LogLimited("dropped frame", $"[{source}] Dropped frame: bad payload ({payload.Length} bytes, type '{SafeType(payload)}')");
                return;
            }

            string type = parsed.Value.commandType;

            // Clipboard text is never logged, so "clp" is handled before the command is decoded
            if (type == "clp")
            {
                if (paste == null)
                {
                    _logger.LogLimited("clp dropped", $"[{source}] clp over UDP ignored");
                    return;
                }

                try
                {
                    ProcessClipboard(parsed.Value.data, source, paste);
                }
                catch (Exception ex)
                {
                    _logger.Log($"[{source}] Error executing clp: {ex.Message}");
                }
                return;
            }

            // URLs are never logged (they can be private), so "sys" logs for itself
            if (type == "sys")
            {
                if (reply == null)
                {
                    _logger.LogLimited("sys dropped", $"[{source}] sys over UDP ignored");
                    return;
                }

                string sys = Encoding.UTF8.GetString(parsed.Value.data);
                try
                {
                    _system.Execute(sys, source, reply);
                }
                catch (Exception ex)
                {
                    // Only the command name (pwr, url, win, clp): the rest may be a URL. Not for the
                    // heartbeat: its reply failing just means the session is ending (the next read
                    // ends it), and a vanished tablet must not leave a line every 5 s
                    if (sys != "png")
                    {
                        _logger.Log($"[{source}] Error executing sys {SafeType(parsed.Value.data)}: {ex.Message}");
                    }
                }
                return;
            }

            // A login frame outside the login step does nothing; its proof is never logged
            if (type == "ath")
            {
                _logger.LogLimited("ath ignored", $"[{source}] ath ignored");
                return;
            }

            string command = Encoding.UTF8.GetString(parsed.Value.data);

            try
            {
                switch (type)
                {
                    case "mos":
                        _held.Touch(peer);
                        ProcessMouse(command, peer);
                        break;
                    case "key": ProcessKey(command); break;
                    default:
                        _logger.LogLimited("unknown command", $"[{source}] Unknown command type: {type}");
                        return;
                }
            }
            catch (Exception ex)
            {
                _logger.LogLimited("command error", $"[{source}] Error executing {Describe(type, command)}: {ex.Message}");
                return;
            }

            // Mouse moves are too frequent to log individually; typed text not at all (live typing
            // sends one frame per keystroke, so even a length per line would be a keystroke log)
            if (!(type == "mos" && command.StartsWith("m ")) && !(type == "key" && command.StartsWith("t")))
            {
                _logger.Log($"[{source}] {Describe(type, command)}");
            }
        }

        /// <summary>
        /// How a command appears in the log. Typed text and typed characters never do: "key"
        /// commands log only their size, and of a combo only the key names (CTRL, ENTER, F5);
        /// characters, number-pad digits and operators, and unknown names show as "?".
        /// </summary>
        private static string Describe(string type, string command)
        {
            string text;
            if (type == "key" && command.StartsWith("t"))
            {
                text = $"key text ({command.Length - 1} chars)";
            }
            else if (type == "key" && command.StartsWith("[") && command.EndsWith("]") && command.Length >= 2)
            {
                var names = command.Substring(1, command.Length - 2).Split('+');
                text = "key [" + string.Join("+", Array.ConvertAll(names, n => LoggedKeyName(n.Trim()))) + "]";
            }
            else if (type == "key")
            {
                text = $"key ({command.Length} chars)";
            }
            else
            {
                text = $"{type} {command}";
            }

            // mos commands are short; cap what a malformed one can put in the log
            return text.Length > 64 ? text.Substring(0, 64) + "..." : text;
        }

        /// <summary>A key name as logged; number-pad keys type characters (e.g. a PIN), so they show as "?"</summary>
        private static string LoggedKeyName(string name)
        {
            bool typesCharacter = name.StartsWith("NUMPAD", StringComparison.OrdinalIgnoreCase)
                || name.ToUpperInvariant() is "MULTIPLY" or "ADD" or "SUBTRACT" or "DECIMAL" or "DIVIDE";
            return InputController.IsNamedKey(name) && !typesCharacter ? name : "?";
        }

        /// <summary>The 3-byte type if it is printable ASCII, else "?"</summary>
        private static string SafeType(byte[] payload)
        {
            if (payload.Length < 3) return "?";
            for (int i = 0; i < 3; i++)
            {
                if (payload[i] < 0x20 || payload[i] > 0x7E) return "?";
            }
            return Encoding.ASCII.GetString(payload, 0, 3);
        }

        private void ProcessMouse(string command, IPAddress peer)
        {
            string[] parts = command.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2) return;

            switch (parts[0])
            {
                case "m" when parts.Length >= 3:
                    _input.MoveMouseRelative(int.Parse(parts[1]), int.Parse(parts[2]));
                    break;
                case "c": _input.Click(ToButton(parts[1])); break;
                case "d": _input.DoubleClick(ToButton(parts[1])); break;
                case "p": _held.Press(ToButton(parts[1]), peer); break;
                case "r": _held.Release(ToButton(parts[1])); break;
                case "s": _input.Scroll(int.Parse(parts[1])); break;
                case "z": _input.Zoom(int.Parse(parts[1])); break;
            }
        }

        private void ProcessClipboard(byte[] body, string source, PasteTransfer paste)
        {
            if (body.Length == 0)
            {
                _logger.Log($"[{source}] clp ignored: empty body");
                return;
            }

            string? error = null;
            switch ((char)body[0])
            {
                case 'b':
                    paste.Begin();
                    break;
                case 'd':
                    error = paste.Append(body, 1, body.Length - 1);
                    break;
                case 'e':
                    byte[]? bytes = paste.End(out error);
                    if (bytes != null) Paste(bytes, source);
                    break;
                default:
                    error = $"clp ignored: unknown op 0x{body[0]:X2}";
                    break;
            }

            if (error != null) _logger.Log($"[{source}] {error}");
        }

        private void Paste(byte[] bytes, string source)
        {
            string text;
            try
            {
                text = StrictUtf8.GetString(bytes);
            }
            catch (DecoderFallbackException)
            {
                _logger.Log($"[{source}] clp dropped: invalid UTF-8 ({bytes.Length} bytes)");
                return;
            }

            if (text.Length == 0) return;

            // CRLF stays, lone LF or CR becomes CRLF
            text = Regex.Replace(text, "\r\n|\r|\n", "\r\n");

            // One paste at a time, so two clients can't swap clipboards between set and the read
            lock (_pasteLock)
            {
                _input.SetClipboardText(text);
                _input.WaitForClipboardIdle();
                _input.PressCombo(new[] { "CTRL", "V" });

                // SendInput only queues Ctrl+V; the target app reads the clipboard when it gets to
                // it. Hold the lock a little longer so the next paste can't replace it before then.
                Thread.Sleep(400);
            }

            _logger.Log($"[{source}] clp paste {text.Length} chars");
        }

        private void ProcessKey(string command)
        {
            if (command.StartsWith("t"))
            {
                _input.TypeText(command.Substring(1));
            }
            else if (command.StartsWith("[") && command.EndsWith("]"))
            {
                _input.PressCombo(command.Substring(1, command.Length - 2).Split('+'));
            }
        }

        private static MouseButton ToButton(string value) => value switch
        {
            "2" => MouseButton.Right,
            "3" => MouseButton.Middle,
            _ => MouseButton.Left
        };
    }
}
