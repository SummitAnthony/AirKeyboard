using Microsoft.Win32;

namespace AirKeyboard
{
    /// <summary>
    /// "Start with Windows": HKCU\Software\Microsoft\Windows\CurrentVersion\Run value AirKeyboard
    /// holding the quoted exe path. The registry is the only state. Task Manager's Startup tab
    /// (and Settings > Apps > Startup) turns it off without touching that value: it writes a
    /// StartupApproved\Run value whose first byte is odd, so that counts as off too.
    /// </summary>
    public static class Autostart
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ApprovedKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
        private const string ValueName = "AirKeyboard";

        public static bool IsEnabled
        {
            get
            {
                using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey);
                if (key?.GetValue(ValueName) == null) return false;
                using RegistryKey? approved = Registry.CurrentUser.OpenSubKey(ApprovedKey);
                // 02 00 .. = enabled, 03 00 .. + a timestamp = disabled by the user
                return !(approved?.GetValue(ValueName) is byte[] state && state.Length > 0 && (state[0] & 1) == 1);
            }
        }

        /// <summary>
        /// At tray start: if Start with Windows is on but names an exe that no longer exists (it
        /// was moved), point it at this exe. A value naming another copy that still exists (say a
        /// dev build next to the published exe) is left alone. Never turns it on.
        /// </summary>
        public static void FixMovedPath()
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            string current = $"\"{Environment.ProcessPath}\"";
            if (key?.GetValue(ValueName) is string value && !string.Equals(value, current, StringComparison.OrdinalIgnoreCase)
                && !File.Exists(ExePath(value)))
            {
                key.SetValue(ValueName, current);
            }
        }

        /// <summary>The exe a Run value names: the quoted part, or the whole value when unquoted</summary>
        private static string ExePath(string value)
        {
            string v = value.Trim();
            if (!v.StartsWith('"')) return v;
            int end = v.IndexOf('"', 1);
            return end < 0 ? v.Substring(1) : v.Substring(1, end - 1);
        }

        /// <summary>
        /// Enabling (re)writes this exe's path, so it also fixes a value left by a moved exe, and
        /// removes a "disabled" mark left by Task Manager. Disabling removes both values.
        /// </summary>
        public static void Set(bool enabled)
        {
            using RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (enabled)
            {
                key.SetValue(ValueName, $"\"{Environment.ProcessPath}\"");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
            using RegistryKey? approved = Registry.CurrentUser.OpenSubKey(ApprovedKey, writable: true);
            approved?.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
