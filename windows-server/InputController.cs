using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

namespace AirKeyboard
{
    public enum MouseButton
    {
        Left = 0,
        Right = 1,
        Middle = 2
    }

    /// <summary>
    /// Injects mouse and keyboard input using the Windows SendInput API
    /// </summary>
    public class InputController
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public InputUnion u;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public int mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern IntPtr GetOpenClipboardWindow();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool OpenClipboard(IntPtr hWndNewOwner);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool CloseClipboard();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool EmptyClipboard();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SetClipboardData(uint uFormat, IntPtr hMem);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr GetClipboardData(uint uFormat);

        [DllImport("user32.dll")]
        private static extern bool IsClipboardFormatAvailable(uint format);

        [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern uint RegisterClipboardFormat(string lpszFormat);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr GlobalAlloc(uint uFlags, UIntPtr dwBytes);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr GlobalLock(IntPtr hMem);

        [DllImport("kernel32.dll")]
        private static extern bool GlobalUnlock(IntPtr hMem);

        [DllImport("kernel32.dll")]
        private static extern IntPtr GlobalFree(IntPtr hMem);

        [DllImport("kernel32.dll")]
        private static extern UIntPtr GlobalSize(IntPtr hMem);

        private const uint CF_UNICODETEXT = 13;
        private const uint GMEM_MOVEABLE = 0x0002;

        private const uint INPUT_MOUSE = 0;
        private const uint INPUT_KEYBOARD = 1;

        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;

        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;

        private const int WHEEL_DELTA = 120;

        private static readonly Dictionary<string, ushort> NamedKeys = new(StringComparer.OrdinalIgnoreCase)
        {
            ["ENTER"] = 0x0D, ["RETURN"] = 0x0D, ["BACKSPACE"] = 0x08, ["TAB"] = 0x09,
            ["ESCAPE"] = 0x1B, ["ESC"] = 0x1B, ["SPACE"] = 0x20, ["DELETE"] = 0x2E,
            ["INSERT"] = 0x2D, ["HOME"] = 0x24, ["END"] = 0x23, ["PAGEUP"] = 0x21, ["PAGEDOWN"] = 0x22,
            ["LEFT"] = 0x25, ["UP"] = 0x26, ["RIGHT"] = 0x27, ["DOWN"] = 0x28,
            ["SHIFT"] = 0xA0, ["CTRL"] = 0xA2, ["CONTROL"] = 0xA2, ["ALT"] = 0xA4, ["WIN"] = 0x5B,
            ["VOLUME_MUTE"] = 0xAD, ["VOLUME_DOWN"] = 0xAE, ["VOLUME_UP"] = 0xAF,
            ["MEDIA_NEXT"] = 0xB0, ["MEDIA_PREVIOUS"] = 0xB1, ["MEDIA_STOP"] = 0xB2, ["MEDIA_PLAY_PAUSE"] = 0xB3,
            ["BROWSER_BACK"] = 0xA6, ["BROWSER_FORWARD"] = 0xA7, ["BROWSER_REFRESH"] = 0xA8,
            ["PRINTSCREEN"] = 0x2C,
            ["NUMPAD0"] = 0x60, ["NUMPAD1"] = 0x61, ["NUMPAD2"] = 0x62, ["NUMPAD3"] = 0x63, ["NUMPAD4"] = 0x64,
            ["NUMPAD5"] = 0x65, ["NUMPAD6"] = 0x66, ["NUMPAD7"] = 0x67, ["NUMPAD8"] = 0x68, ["NUMPAD9"] = 0x69,
            ["MULTIPLY"] = 0x6A, ["ADD"] = 0x6B, ["SUBTRACT"] = 0x6D, ["DECIMAL"] = 0x6E, ["DIVIDE"] = 0x6F,
            ["NUMLOCK"] = 0x90, ["CAPSLOCK"] = 0x14,
        };

        // Keys that need KEYEVENTF_EXTENDEDKEY to be distinguished from numpad keys (numpad divide
        // and Num Lock are extended keys themselves)
        private static readonly HashSet<ushort> ExtendedKeys = new()
        {
            0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x25, 0x26, 0x27, 0x28, 0x5B, 0x6F, 0x90
        };

        public void MoveMouseRelative(int dx, int dy) => SendMouse(MOUSEEVENTF_MOVE, dx, dy, 0);

        public void Click(MouseButton button)
        {
            ButtonDown(button);
            ButtonUp(button);
        }

        public void DoubleClick(MouseButton button)
        {
            Click(button);
            Click(button);
        }

        public void ButtonDown(MouseButton button) => SendMouse(button switch
        {
            MouseButton.Right => MOUSEEVENTF_RIGHTDOWN,
            MouseButton.Middle => MOUSEEVENTF_MIDDLEDOWN,
            _ => MOUSEEVENTF_LEFTDOWN
        }, 0, 0, 0);

        public void ButtonUp(MouseButton button) => SendMouse(button switch
        {
            MouseButton.Right => MOUSEEVENTF_RIGHTUP,
            MouseButton.Middle => MOUSEEVENTF_MIDDLEUP,
            _ => MOUSEEVENTF_LEFTUP
        }, 0, 0, 0);

        /// <summary>Positive notches scroll up, negative scroll down</summary>
        public void Scroll(int notches) => SendMouse(MOUSEEVENTF_WHEEL, 0, 0, notches * WHEEL_DELTA);

        /// <summary>Ctrl + wheel (browsers, Office, viewers, Explorer); positive zooms in. One SendInput, so Ctrl can't stick</summary>
        public void Zoom(int notches) => Send(new[]
        {
            Key(0xA2, false),
            new INPUT
            {
                type = INPUT_MOUSE,
                u = new InputUnion { mi = new MOUSEINPUT { mouseData = notches * WHEEL_DELTA, dwFlags = MOUSEEVENTF_WHEEL } }
            },
            Key(0xA2, true)
        });

        /// <summary>Types arbitrary text as Unicode, independent of keyboard layout</summary>
        public void TypeText(string text)
        {
            var inputs = new List<INPUT>();
            foreach (char c in text)
            {
                if (c == '\n')
                {
                    inputs.Add(Key(0x0D, false));
                    inputs.Add(Key(0x0D, true));
                    continue;
                }
                inputs.Add(Unicode(c, false));
                inputs.Add(Unicode(c, true));
            }
            Send(inputs.ToArray());
        }

        /// <summary>Presses keys in order then releases them in reverse, e.g. CTRL+SHIFT+T</summary>
        public void PressCombo(string[] keyNames)
        {
            var vks = new List<ushort>();
            foreach (string name in keyNames)
            {
                ushort vk = ToVirtualKey(name.Trim());
                if (vk == 0) throw new ArgumentException($"Unknown key name ({name.Length} chars)"); // the name may be a typed character
                vks.Add(vk);
            }

            var inputs = new List<INPUT>();
            foreach (ushort vk in vks) inputs.Add(Key(vk, false));
            for (int i = vks.Count - 1; i >= 0; i--) inputs.Add(Key(vks[i], true));
            Send(inputs.ToArray());
        }

        /// <summary>
        /// Puts Unicode text on the Windows clipboard, plus marker formats that keep it (often a
        /// password) out of Win+V clipboard history and cloud clipboard sync. Uses Win32 directly
        /// so every format is rendered up front: WinForms/OLE uses delayed rendering, and a clipboard
        /// listener asking for a format can then wait on this thread while holding the clipboard open,
        /// so OleFlushClipboard's retries all fail ("Requested Clipboard operation did not succeed").
        /// Runs on a short-lived thread, because EmptyClipboard messages the previous owner.
        /// </summary>
        public void SetClipboardText(string text)
        {
            Exception? error = null;
            var thread = new Thread(() =>
            {
                try
                {
                    SetClipboardTextNow(text);
                }
                catch (Exception ex)
                {
                    error = ex;
                }
            }) { IsBackground = true };
            thread.Start();

            // A hung clipboard owner can block inside Win32 forever; give up rather than hold
            // the caller (and its paste lock). The abandoned background thread ends on its own.
            if (!thread.Join(TimeSpan.FromSeconds(5)))
            {
                throw new TimeoutException("Clipboard unavailable: clipboard set timed out");
            }

            if (error != null) throw new InvalidOperationException($"Clipboard unavailable: {error.Message}");
        }

        private static void SetClipboardTextNow(string text)
        {
            OpenClipboardRetrying(3000);
            try
            {
                if (!EmptyClipboard()) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not empty the clipboard");
                Put(CF_UNICODETEXT, System.Text.Encoding.Unicode.GetBytes(text + "\0"));
                Put(RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing"), new byte[4]);
                Put(RegisterClipboardFormat("CanIncludeInClipboardHistory"), BitConverter.GetBytes(0));
                Put(RegisterClipboardFormat("CanUploadToCloudClipboard"), BitConverter.GetBytes(0));
            }
            finally
            {
                CloseClipboard();
            }
        }

        /// <summary>Another app may hold the clipboard open for a moment (listeners read it after each change)</summary>
        private static void OpenClipboardRetrying(int maxMs)
        {
            var wait = System.Diagnostics.Stopwatch.StartNew();
            while (!OpenClipboard(IntPtr.Zero))
            {
                if (wait.ElapsedMilliseconds > maxMs)
                    throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not open the clipboard");
                Thread.Sleep(20);
            }
        }

        /// <summary>
        /// The clipboard's Unicode text, or null if it holds none. Reads at most maxChars UTF-16
        /// units (a longer text comes back cut, so the caller can still tell it is too large). Runs
        /// on a short-lived thread like SetClipboardText: GetClipboardData waits on the owner of
        /// delay-rendered data, which may hang.
        /// </summary>
        public string? GetClipboardText(int maxChars)
        {
            string? text = null;
            Exception? error = null;
            var thread = new Thread(() =>
            {
                try
                {
                    text = GetClipboardTextNow(maxChars);
                }
                catch (Exception ex)
                {
                    error = ex;
                }
            }) { IsBackground = true };
            thread.Start();

            if (!thread.Join(TimeSpan.FromSeconds(3)))
            {
                throw new TimeoutException("Clipboard unavailable: clipboard read timed out");
            }

            if (error != null) throw new InvalidOperationException($"Clipboard unavailable: {error.Message}");
            return text;
        }

        private static string? GetClipboardTextNow(int maxChars)
        {
            if (!IsClipboardFormatAvailable(CF_UNICODETEXT)) return null;
            OpenClipboardRetrying(1000);
            try
            {
                IntPtr mem = GetClipboardData(CF_UNICODETEXT);
                if (mem == IntPtr.Zero) return null;
                IntPtr p = GlobalLock(mem);
                if (p == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not lock clipboard memory");
                try
                {
                    // The block holds the text and a terminating NUL, and may be larger than both
                    int chars = (int)Math.Min((ulong)GlobalSize(mem) / 2, (ulong)maxChars);
                    string text = Marshal.PtrToStringUni(p, chars);
                    int nul = text.IndexOf('\0');
                    return nul >= 0 ? text.Substring(0, nul) : text;
                }
                finally
                {
                    GlobalUnlock(mem);
                }
            }
            finally
            {
                CloseClipboard();
            }
        }

        /// <summary>Copies bytes into global memory and hands it to the open clipboard, which then owns it</summary>
        private static void Put(uint format, byte[] bytes)
        {
            if (format == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not register a clipboard format");
            IntPtr mem = GlobalAlloc(GMEM_MOVEABLE, (UIntPtr)bytes.Length);
            if (mem == IntPtr.Zero) throw new OutOfMemoryException("GlobalAlloc failed for the clipboard");
            IntPtr p = GlobalLock(mem);
            if (p == IntPtr.Zero)
            {
                GlobalFree(mem);
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not lock clipboard memory");
            }
            Marshal.Copy(bytes, 0, p, bytes.Length);
            GlobalUnlock(mem);
            if (SetClipboardData(format, mem) == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                GlobalFree(mem);
                throw new System.ComponentModel.Win32Exception(err, "could not set clipboard data");
            }
        }

        /// <summary>
        /// Clipboard listeners (clipboard history, cloud clipboard, GPU overlays) open the clipboard
        /// to read it for up to ~250 ms after it changes, and a Ctrl+V landing then pastes nothing.
        /// Waits until nobody has held it open for <paramref name="quietMs"/>, at most <paramref name="maxMs"/>.
        /// </summary>
        public void WaitForClipboardIdle(int quietMs = 150, int maxMs = 1500)
        {
            var total = System.Diagnostics.Stopwatch.StartNew();
            var quiet = System.Diagnostics.Stopwatch.StartNew();
            while (quiet.ElapsedMilliseconds < quietMs && total.ElapsedMilliseconds < maxMs)
            {
                Thread.Sleep(10);
                if (GetOpenClipboardWindow() != IntPtr.Zero) quiet.Restart();
            }
        }

        /// <summary>True for a key name such as CTRL, ENTER or F5; false for single characters</summary>
        public static bool IsNamedKey(string name) => name.Length > 1 && ToVirtualKey(name) != 0;

        private static ushort ToVirtualKey(string name)
        {
            if (NamedKeys.TryGetValue(name, out ushort vk)) return vk;

            // A-Z and 0-9 map directly to their uppercase ASCII codes
            if (name.Length == 1 && char.IsAsciiLetterOrDigit(name[0])) return char.ToUpperInvariant(name[0]);

            // F1-F24
            if (name.Length >= 2 && (name[0] == 'F' || name[0] == 'f') &&
                int.TryParse(name.AsSpan(1), out int f) && f >= 1 && f <= 24)
            {
                return (ushort)(0x70 + f - 1);
            }

            return 0;
        }

        private static INPUT Key(ushort vk, bool up) => new()
        {
            type = INPUT_KEYBOARD,
            u = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    dwFlags = (up ? KEYEVENTF_KEYUP : 0) | (ExtendedKeys.Contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0)
                }
            }
        };

        private static INPUT Unicode(char c, bool up) => new()
        {
            type = INPUT_KEYBOARD,
            u = new InputUnion
            {
                ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) }
            }
        };

        private static void SendMouse(uint flags, int dx, int dy, int data) => Send(new[]
        {
            new INPUT
            {
                type = INPUT_MOUSE,
                u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } }
            }
        });

        private static void Send(INPUT[] inputs)
        {
            if (inputs.Length == 0) return;
            uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
            if (sent != inputs.Length)
            {
                throw new InvalidOperationException($"SendInput sent {sent}/{inputs.Length} (error {Marshal.GetLastWin32Error()})");
            }
        }
    }
}
