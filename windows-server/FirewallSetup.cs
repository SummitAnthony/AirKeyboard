using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;

namespace AirKeyboard
{
    /// <summary>
    /// Inbound firewall rules for the app's ports, local subnet only: TCP 1978 (handshake and
    /// control), UDP 1978 (commands) and UDP 2008 (discovery). The tray checks them on start and,
    /// when missing, relaunches itself elevated with --setup-firewall to add them.
    /// </summary>
    public static class FirewallSetup
    {
        private const string TcpRule = "AirKeyboard (TCP 1978)";
        private const string UdpRule = "AirKeyboard (UDP 1978,2008)";

        /// <summary>The user said no once (our prompt or UAC); only the tray menu asks again</summary>
        private static string DeclinedFlag => Path.Combine(AuthConfig.AppDataFolder, "firewall-declined");

        public static bool Declined => File.Exists(DeclinedFlag);

        /// <summary>Both rules are there; also true when netsh can't be run, so the tray doesn't nag</summary>
        public static bool RulesExist()
        {
            try
            {
                return Netsh($"show rule name=\"{TcpRule}\"") == 0 && Netsh($"show rule name=\"{UdpRule}\"") == 0;
            }
            catch (Exception ex) when (ex is Win32Exception || ex is InvalidOperationException)
            {
                return true;
            }
        }

        /// <summary>
        /// --setup-firewall (elevated): replaces our rules with fresh ones. 0 = both added, 1 = netsh
        /// failed.
        /// </summary>
        public static int AddRules()
        {
            Netsh($"delete rule name=\"{TcpRule}\"");
            Netsh($"delete rule name=\"{UdpRule}\"");

            const string common = "dir=in action=allow remoteip=localsubnet profile=any";
            int tcp = Netsh($"add rule name=\"{TcpRule}\" {common} protocol=TCP localport=1978");
            int udp = Netsh($"add rule name=\"{UdpRule}\" {common} protocol=UDP localport=1978,2008");
            return tcp == 0 && udp == 0 ? 0 : 1;
        }

        /// <summary>
        /// Tray: explains, then runs AddRules elevated (Windows asks for permission). True when the
        /// rules were added. A "no" here or on the UAC prompt sets the declined flag.
        /// </summary>
        public static bool AskAndSetup()
        {
            DialogResult answer = MessageBox.Show(
                "AirKeyboard needs a Windows Firewall rule so your device can reach this PC on your " +
                "local network (TCP port 1978, UDP ports 1978 and 2008).\n\n" +
                "Windows will ask for permission next.",
                "AirKeyboard", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
            if (answer != DialogResult.OK)
            {
                SetDeclined(true);
                return false;
            }

            int exitCode;
            try
            {
                using Process? p = Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--setup-firewall")
                {
                    UseShellExecute = true,
                    Verb = "runas"
                });
                if (p == null) return false;
                p.WaitForExit();
                exitCode = p.ExitCode;
            }
            catch (Win32Exception ex) when (ex.NativeErrorCode == 1223) // ERROR_CANCELLED: No on UAC
            {
                SetDeclined(true);
                MessageBox.Show(
                    "No firewall rule was added, so your device may not be able to connect.\n\n" +
                    "To try again, right-click the AirKeyboard tray icon and choose Set up firewall.",
                    "AirKeyboard", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return false;
            }

            if (exitCode != 0)
            {
                MessageBox.Show("Adding the firewall rules failed (netsh error).", "AirKeyboard",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
            SetDeclined(false);
            return true;
        }

        private static void SetDeclined(bool declined)
        {
            try
            {
                if (declined)
                {
                    Directory.CreateDirectory(AuthConfig.AppDataFolder);
                    File.WriteAllText(DeclinedFlag, "");
                }
                else
                {
                    File.Delete(DeclinedFlag);
                }
            }
            catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
            {
                // Only costs a repeated question on the next start
            }
        }

        private static int Netsh(string command)
        {
            // Full path: a bare name would first find a netsh.exe placed next to AirKeyboard.exe,
            // and --setup-firewall runs elevated
            using Process p = Process.Start(new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "netsh.exe"), "advfirewall firewall " + command)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true
            })!;
            p.StandardOutput.ReadToEnd();
            p.WaitForExit();
            return p.ExitCode;
        }
    }
}
