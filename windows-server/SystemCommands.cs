using System;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;

namespace AirKeyboard
{
    /// <summary>
    /// "sys" commands, from the TCP session only:
    ///   pwr shutdown | restart | logoff | sleep | lock
    ///   url &lt;http(s) URL&gt;    open in the default browser
    ///   win list               reply "wnl" "&lt;id&gt;\t&lt;title&gt;" per app window (front-most first), then "wne" ""
    ///   win focus &lt;id&gt;       restore and bring to front; win close &lt;id&gt;: WM_CLOSE (id = HWND, decimal)
    ///   clp get                reply "cbb" "", "cbd" slices of the clipboard text, "cbe" "" ("cbe" "toolarge"
    ///                          over 1 MiB of UTF-8; "cbb" + "cbe" when there is no text)
    ///   png                    heartbeat (the app sends it every 5 s): reply "png" "", never logged
    /// Only the command name is logged: never a URL, the clipboard text or a window title.
    /// reply sends one server message (marker, body of at most 999 bytes) on the requesting session.
    /// </summary>
    public class SystemCommands
    {
        private const int MAX_URL_CHARS = 2048;
        private const int MAX_WINDOWS = 50;
        private const int MAX_TITLE_CHARS = 120;
        private const int MAX_MESSAGE_BYTES = 999;
        private const int CLIPBOARD_SLICE_BYTES = 900;

        [DllImport("powrprof.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SetSuspendState([MarshalAs(UnmanagedType.I1)] bool hibernate,
            [MarshalAs(UnmanagedType.I1)] bool force, [MarshalAs(UnmanagedType.I1)] bool wakeupEventsDisabled);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool LockWorkStation();

        private readonly InputController _input;
        private readonly ActivityLogger _logger;

        public SystemCommands(InputController input, ActivityLogger logger)
        {
            _input = input;
            _logger = logger;
        }

        public void Execute(string command, string source, Action<string, byte[]> reply)
        {
            int space = command.IndexOf(' ');
            string name = space < 0 ? command : command.Substring(0, space);
            string arg = space < 0 ? "" : command.Substring(space + 1);

            switch (name)
            {
                case "pwr": Power(arg, source); break;
                case "url": OpenUrl(arg, source); break;
                case "win": Window(arg, source, reply); break;
                case "clp" when arg == "get": SendClipboard(source, reply); break;
                case "png": reply("png", Array.Empty<byte>()); break; // too frequent to log
                default:
                    _logger.LogLimited("sys ignored", $"[{source}] sys ignored: unknown command ({command.Length} chars)");
                    break;
            }
        }

        private void Power(string arg, string source)
        {
            if (arg is not ("shutdown" or "restart" or "logoff" or "sleep" or "lock"))
            {
                _logger.LogLimited("sys ignored", $"[{source}] sys pwr ignored: unknown action");
                return;
            }

            // Logged first: shutting down may end this process before the call returns
            _logger.Log($"[{source}] sys pwr {arg}");
            switch (arg)
            {
                case "shutdown": RunShutdown("/s /t 0"); break;
                case "restart": RunShutdown("/r /t 0"); break;
                case "logoff": RunShutdown("/l"); break;
                case "sleep":
                    if (!SetSuspendState(false, false, false))
                        throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "SetSuspendState failed");
                    break;
                case "lock":
                    if (!LockWorkStation())
                        throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "LockWorkStation failed");
                    break;
            }
        }

        private static void RunShutdown(string arguments)
        {
            // Full path: a bare name would first find a shutdown.exe placed next to AirKeyboard.exe
            using var process = Process.Start(new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe"), arguments)
            {
                UseShellExecute = false,
                CreateNoWindow = true
            });
        }

        private void OpenUrl(string url, string source)
        {
            if (url.Length > MAX_URL_CHARS || !Uri.TryCreate(url, UriKind.Absolute, out Uri? uri)
                || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            {
                _logger.LogLimited("sys ignored", $"[{source}] sys url dropped: not an http(s) URL ({url.Length} chars)");
                return;
            }

            // AbsoluteUri is the parsed, escaped form: nothing in it reads as a shell argument
            using var process = Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
            _logger.Log($"[{source}] sys url ({url.Length} chars)");
        }

        private void Window(string arg, string source, Action<string, byte[]> reply)
        {
            if (arg == "list")
            {
                var windows = WindowList.AppWindows(MAX_WINDOWS);
                foreach (var (hwnd, title) in windows)
                {
                    string body = hwnd.ToInt64().ToString(CultureInfo.InvariantCulture) + "\t" + Clean(title);
                    reply("wnl", Utf8Prefix(body, MAX_MESSAGE_BYTES));
                }
                reply("wne", Array.Empty<byte>());
                _logger.Log($"[{source}] sys win list ({windows.Count} windows)");
                return;
            }

            int space = arg.IndexOf(' ');
            string action = space < 0 ? arg : arg.Substring(0, space);
            if (action is not ("focus" or "close"))
            {
                _logger.LogLimited("sys ignored", $"[{source}] sys win ignored: unknown action");
                return;
            }

            // Only a window the list would show: never a hidden, tool or system window by guessed id
            if (space < 0 || !long.TryParse(arg.AsSpan(space + 1), NumberStyles.None, CultureInfo.InvariantCulture, out long id)
                || !WindowList.IsListed(new IntPtr(id)))
            {
                _logger.LogLimited("sys ignored", $"[{source}] sys win {action} ignored: not an app window");
                return;
            }

            if (action == "focus") WindowList.Focus(new IntPtr(id));
            else WindowList.Close(new IntPtr(id));
            _logger.Log($"[{source}] sys win {action}");
        }

        /// <summary>A title on one line, at most MAX_TITLE_CHARS, never splitting a surrogate pair</summary>
        private static string Clean(string title)
        {
            var sb = new StringBuilder(title.Length);
            foreach (char c in title) sb.Append(char.IsControl(c) ? ' ' : c);
            if (sb.Length > MAX_TITLE_CHARS)
            {
                int length = char.IsHighSurrogate(sb[MAX_TITLE_CHARS - 1]) ? MAX_TITLE_CHARS - 1 : MAX_TITLE_CHARS;
                sb.Length = length;
            }
            return sb.ToString();
        }

        private void SendClipboard(string source, Action<string, byte[]> reply)
        {
            string? text = null;
            try
            {
                // One unit more than fits: a cut text then still counts as too large
                text = _input.GetClipboardText(PasteTransfer.MAX_BYTES + 1);
            }
            catch (Exception ex)
            {
                // Answered as "no text", so the tablet doesn't wait out its timeout
                _logger.Log($"[{source}] sys clp get failed: {ex.Message}");
            }

            reply("cbb", Array.Empty<byte>());
            byte[] bytes = Encoding.UTF8.GetBytes(text ?? "");
            if (bytes.Length > PasteTransfer.MAX_BYTES)
            {
                reply("cbe", Encoding.ASCII.GetBytes("toolarge"));
                _logger.Log($"[{source}] sys clp get: too large");
                return;
            }

            for (int start = 0; start < bytes.Length;)
            {
                int end = Math.Min(start + CLIPBOARD_SLICE_BYTES, bytes.Length);
                // Back off while the next byte is a continuation byte (10xxxxxx)
                while (end < bytes.Length && (bytes[end] & 0xC0) == 0x80) end--;
                reply("cbd", bytes[start..end]);
                start = end;
            }
            reply("cbe", Array.Empty<byte>());
            _logger.Log($"[{source}] sys clp get");
        }

        /// <summary>s as UTF-8, cut to at most maxBytes without splitting a code point</summary>
        private static byte[] Utf8Prefix(string s, int maxBytes)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(s);
            if (bytes.Length <= maxBytes) return bytes;
            int end = maxBytes;
            while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
            return bytes[..end];
        }
    }
}
