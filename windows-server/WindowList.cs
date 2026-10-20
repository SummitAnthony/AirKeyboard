using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

namespace AirKeyboard
{
    /// <summary>
    /// The PC's app windows (what the taskbar shows) for "sys win": list, focus, close.
    /// A window counts if it is top-level, visible, has a title, has no owner, is not a tool
    /// window, is not cloaked by DWM (e.g. on another virtual desktop) and is not the desktop.
    /// </summary>
    public static class WindowList
    {
        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll")]
        private static extern IntPtr GetWindow(IntPtr hWnd, uint uCmd);

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll")]
        private static extern IntPtr GetShellWindow();

        [DllImport("dwmapi.dll")]
        private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out int pvAttribute, int cbAttribute);

        [DllImport("user32.dll")]
        private static extern bool IsIconic(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool BringWindowToTop(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

        private const uint GW_OWNER = 4;
        private const int GWL_EXSTYLE = -20;
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const int DWMWA_CLOAKED = 14;
        private const int SW_RESTORE = 9;
        private const uint WM_CLOSE = 0x0010;

        /// <summary>App windows front-most first (EnumWindows walks the z-order), at most max</summary>
        public static List<(IntPtr hwnd, string title)> AppWindows(int max)
        {
            var windows = new List<(IntPtr, string)>();
            IntPtr shell = GetShellWindow();
            EnumWindows((hwnd, _) =>
            {
                if (hwnd != shell && IsAppWindow(hwnd, out string title)) windows.Add((hwnd, title));
                return windows.Count < max;
            }, IntPtr.Zero);
            return windows;
        }

        /// <summary>True if hwnd is one of AppWindows now (ids come from the client, so check before acting)</summary>
        public static bool IsListed(IntPtr hwnd) => AppWindows(int.MaxValue).Exists(w => w.hwnd == hwnd);

        private static bool IsAppWindow(IntPtr hwnd, out string title)
        {
            title = "";
            if (!IsWindowVisible(hwnd) || GetWindow(hwnd, GW_OWNER) != IntPtr.Zero) return false;
            if ((GetWindowLong(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) return false;
            if (DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, out int cloaked, sizeof(int)) == 0 && cloaked != 0) return false;

            int length = GetWindowTextLength(hwnd);
            if (length <= 0) return false;
            var text = new StringBuilder(length + 1);
            GetWindowText(hwnd, text, text.Capacity);
            title = text.ToString();
            return title.Length > 0;
        }

        /// <summary>
        /// Restores hwnd if minimized and brings it to the front. Windows lets only the foreground
        /// app hand the foreground on, so this thread shares the foreground thread's input state
        /// for the call (AttachThreadInput), rather than faking an Alt press.
        /// </summary>
        public static void Focus(IntPtr hwnd)
        {
            // Async: a hung app must not hang this connection's thread
            if (IsIconic(hwnd)) ShowWindowAsync(hwnd, SW_RESTORE);

            uint foreground = GetWindowThreadProcessId(GetForegroundWindow(), out _);
            uint self = GetCurrentThreadId();
            bool attached = foreground != 0 && foreground != self && AttachThreadInput(self, foreground, true);
            try
            {
                BringWindowToTop(hwnd);
                SetForegroundWindow(hwnd);
            }
            finally
            {
                if (attached) AttachThreadInput(self, foreground, false);
            }
        }

        /// <summary>Asks the window to close, as its close button does (the app may still ask to save)</summary>
        public static void Close(IntPtr hwnd)
        {
            if (!PostMessage(hwnd, WM_CLOSE, IntPtr.Zero, IntPtr.Zero))
            {
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "could not post WM_CLOSE");
            }
        }
    }
}
