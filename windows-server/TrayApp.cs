using System.ComponentModel;
using System.Diagnostics;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace AirKeyboard
{
    /// <summary>
    /// The default mode: the server behind a notification-area icon. One instance per user
    /// session; a second launch brings up the first one's pairing window and exits.
    /// Logs go to %LOCALAPPDATA%\AirKeyboard\logs (a Run-key start has System32 as its folder).
    /// </summary>
    public sealed class TrayApp : ApplicationContext
    {
        private const string MutexName = @"Local\AirKeyboard";
        private const string ShowPairingEventName = @"Local\AirKeyboard.ShowPairing";
        private const int MaxTooltipChars = 127; // NotifyIcon.Text throws above this
        private const string RemovePasswordText = "Remove password…";

        private static string LogsFolder => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AuthConfig.AppFolderName, "logs");

        private readonly string _serverName =
            Environment.GetEnvironmentVariable("AIRKEYBOARD_NAME") ?? Environment.MachineName;
        private readonly SynchronizationContext _ui;
        private readonly NotifyIcon _icon;
        private readonly ContextMenuStrip _menu = new();
        private readonly ToolStripMenuItem _status = new() { Enabled = false };
        private readonly ToolStripMenuItem _setPassword = new();
        private readonly ToolStripMenuItem _removePassword = new(RemovePasswordText);
        private readonly ToolStripMenuItem _firewall = new("Set up firewall…");
        private readonly ToolStripMenuItem _autostart = new("Start with Windows");
        private readonly RegisteredWaitHandle _showPairingWait;
        private RemoteControlServer? _server;
        private string? _error; // why the server isn't running
        private bool _hasPassword;
        private bool _configDamaged; // config.json can't be read as a config; Remove password rewrites it
        private bool _firewallOk;
        private bool _discoveryWarned;
        private PairingForm? _pairing;

        // A damaged config stops the server, so nobody can control the PC then
        private bool NeedsPasswordWarning => !_hasPassword && !_configDamaged;

        public static int Run()
        {
            using var mutex = new Mutex(true, MutexName, out bool first);
            using var showPairing = new EventWaitHandle(false, EventResetMode.AutoReset, ShowPairingEventName);
            if (!first)
            {
                AllowSetForegroundWindow(-1); // ASFW_ANY: the first instance may bring its window to the front
                showPairing.Set();
                return 0;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.SetHighDpiMode(HighDpiMode.SystemAware);
            SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
            ActivityLogger.LogsDir = LogsFolder;

            using var app = new TrayApp(showPairing);
            Application.Run(app);
            return 0;
        }

        private TrayApp(EventWaitHandle showPairing)
        {
            _ui = SynchronizationContext.Current!;

            var pairing = new ToolStripMenuItem("Show pairing QR…", null, (_, _) => ShowPairing());
            pairing.Font = new Font(pairing.Font, FontStyle.Bold); // the double-click action
            _setPassword.Click += (_, _) => SetPassword();
            _removePassword.Click += (_, _) => RemovePassword();
            _firewall.Click += (_, _) => _firewallOk = FirewallSetup.AskAndSetup() || _firewallOk;
            _autostart.Click += (_, _) => ToggleAutostart();
            // Best effort: if it fails, the menu still shows the state and ticking it rewrites the path
            try { Autostart.FixMovedPath(); }
            catch (Exception ex) when (ex is UnauthorizedAccessException || ex is IOException || ex is System.Security.SecurityException) { }
            _menu.Items.AddRange(new ToolStripItem[]
            {
                _status,
                new ToolStripSeparator(),
                pairing,
                _setPassword,
                _removePassword,
                _firewall,
                _autostart,
                new ToolStripMenuItem("Open logs folder", null, (_, _) => OpenLogs()),
                new ToolStripSeparator(),
                new ToolStripMenuItem("Quit", null, (_, _) => Quit())
            });
            _menu.Opening += (_, _) =>
            {
                _setPassword.Text = _hasPassword ? "Change password…" : "Set password…";
                _removePassword.Enabled = _hasPassword || _configDamaged;
                _firewall.Visible = !_firewallOk;
                _autostart.Checked = Autostart.IsEnabled;
            };

            _icon = new NotifyIcon { Icon = AppIcon.Tray, ContextMenuStrip = _menu, Text = "AirKeyboard", Visible = true };
            _icon.DoubleClick += (_, _) => ShowPairing();
            _showPairingWait = ThreadPool.RegisterWaitForSingleObject(showPairing,
                (_, _) => _ui.Post(_ => ShowPairing(), null), null, Timeout.Infinite, executeOnlyOnce: false);

            // Before the server listens, so Windows' own "allow access" prompt has nothing to ask about
            _firewallOk = FirewallSetup.RulesExist();
            if (!_firewallOk && !FirewallSetup.Declined)
            {
                _firewallOk = FirewallSetup.AskAndSetup();
            }

            // Like Ctrl+C in the console mode: never leave a button held
            AppDomain.CurrentDomain.ProcessExit += (_, _) => _server?.ReleaseHeldButtons();
            StartServer();
        }

        private void StartServer()
        {
            // A config file that can't be read must never mean "no password"
            AuthConfig auth;
            try
            {
                auth = AuthConfig.Load();
            }
            catch (InvalidDataException)
            {
                // Damaged or empty (e.g. a bad hand edit): point at the way out
                _configDamaged = true;
                Stopped($"The settings file is damaged. Right-click the tray icon and choose {RemovePasswordText} to reset it.");
                return;
            }
            catch (Exception ex)
            {
                Stopped(ex.Message);
                return;
            }
            _configDamaged = false;
            _hasPassword = auth.HasPassword;

            // After a restart the old sockets may take a moment to let go of the ports
            for (int attempt = 1; ; attempt++)
            {
                var server = new RemoteControlServer(_serverName, auth);
                // Before StartAsync: it is raised while the ports are bound
                server.DiscoveryUnavailable += _ => OnDiscoveryUnavailable(server);
                Task task;
                // Without the UI context, so the accept loop continues on the thread pool
                SynchronizationContext.SetSynchronizationContext(null);
                try
                {
                    // Everything before StartAsync's first await (binding the ports) has run on return
                    task = server.StartAsync();
                }
                finally
                {
                    SynchronizationContext.SetSynchronizationContext(_ui);
                }

                if (!task.IsFaulted)
                {
                    _server = server;
                    _error = null;
                    server.ClientsChanged += OnClientsChanged;
                    UpdateStatus();
                    return;
                }
                server.Stop();
                Exception ex = task.Exception!.InnerException!;
                if (ex is SocketException se && se.SocketErrorCode == SocketError.AddressAlreadyInUse)
                {
                    if (attempt < 10)
                    {
                        Thread.Sleep(300);
                        continue;
                    }
                    Stopped("port 1978 is in use by another program. Quit it, then restart AirKeyboard.");
                    return;
                }
                Stopped(ex.Message);
                return;
            }
        }

        private void StopServer()
        {
            if (_server == null) return;
            _server.ClientsChanged -= OnClientsChanged;
            _server.Stop(); // also releases held buttons
            _server = null;
        }

        private void RestartServer()
        {
            StopServer();
            StartServer();
        }

        private void Stopped(string reason)
        {
            _error = reason;
            UpdateStatus();
            _icon.ShowBalloonTip(10_000, "AirKeyboard isn't running", reason, ToolTipIcon.Error);
        }

        /// <summary>
        /// UDP 2008 is taken: the server runs, but the app's Auto Discover can't find this PC.
        /// Shown once the start is over, only if this server is the one running (so it never
        /// covers a "not running" balloon), and once per run.
        /// </summary>
        private void OnDiscoveryUnavailable(RemoteControlServer server)
        {
            _ui.Post(_ =>
            {
                if (_server != server || _discoveryWarned) return;
                _discoveryWarned = true;
                _icon.ShowBalloonTip(10_000, "AirKeyboard",
                    "Auto-discovery is off: UDP port 2008 is in use. Connect with the QR code or the PC's address.",
                    ToolTipIcon.Warning);
            }, null);
        }

        private void OnClientsChanged()
        {
            try
            {
                _ui.Post(_ => UpdateStatus(), null);
            }
            catch (InvalidOperationException)
            {
                // A client thread finishing after Quit: nothing left to update
            }
        }

        private void UpdateStatus()
        {
            string tooltip;
            if (_server != null)
            {
                int count = _server.ClientCount;
                _status.Text = $"Running as {_serverName} – {count} connected";
                tooltip = $"AirKeyboard – {_serverName} – {count} connected";
            }
            else
            {
                _status.Text = $"Not running: {_error}";
                tooltip = "AirKeyboard – not running";
            }
            _icon.Text = tooltip.Length > MaxTooltipChars ? tooltip.Substring(0, MaxTooltipChars) : tooltip;
            if (_pairing != null && !_pairing.IsDisposed) _pairing.ShowPasswordWarning(NeedsPasswordWarning);
        }

        private void ShowPairing()
        {
            if (_pairing == null || _pairing.IsDisposed)
            {
                _pairing = new PairingForm(_serverName, NeedsPasswordWarning, SetPassword); // closing it disposes it
            }
            _pairing.Show();
            if (_pairing.WindowState == FormWindowState.Minimized) _pairing.WindowState = FormWindowState.Normal;
            _pairing.Activate();
        }

        private void SetPassword()
        {
            using var dialog = new PasswordDialog(change: _hasPassword);
            if (dialog.ShowDialog() != DialogResult.OK) return;
            bool saved = Save(dialog.Config);
            dialog.Config.Clear();
            if (!saved) return;
            RestartServer();
            _icon.ShowBalloonTip(5000, "AirKeyboard", "Password set. The app asks for it when it next connects.", ToolTipIcon.Info);
        }

        private void RemovePassword()
        {
            DialogResult answer = MessageBox.Show(
                "Remove the password? Anyone on your network with the app can then control this PC.",
                "AirKeyboard", MessageBoxButtons.YesNo, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes || !Save(new AuthConfig())) return;
            RestartServer();
        }

        private static bool Save(AuthConfig config)
        {
            try
            {
                config.Save();
                return true;
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Could not write {AuthConfig.FilePath}: {ex.Message}", "AirKeyboard",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
        }

        private static void ToggleAutostart()
        {
            try
            {
                Autostart.Set(!Autostart.IsEnabled);
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException || ex is IOException || ex is System.Security.SecurityException)
            {
                MessageBox.Show($"Could not change the startup setting: {ex.Message}", "AirKeyboard",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void OpenLogs()
        {
            Directory.CreateDirectory(LogsFolder);
            try
            {
                Process.Start(new ProcessStartInfo(LogsFolder) { UseShellExecute = true })?.Dispose();
            }
            catch (Win32Exception ex)
            {
                MessageBox.Show($"Could not open {LogsFolder}: {ex.Message}", "AirKeyboard",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void Quit()
        {
            _showPairingWait.Unregister(null);
            StopServer();
            _pairing?.Close();
            _icon.Visible = false;
            ExitThread();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _icon.Dispose();
                _menu.Dispose();
            }
            base.Dispose(disposing);
        }

        [DllImport("user32.dll")]
        private static extern bool AllowSetForegroundWindow(int processId);
    }
}
