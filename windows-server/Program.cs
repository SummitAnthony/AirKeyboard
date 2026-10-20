using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using AirKeyboard;

class Program
{
    private static readonly string[] ConsoleModes = { "--console", "--set-password", "--clear-password", "--print-pairing" };
    private const string FirewallMode = "--setup-firewall";

    private static readonly string[] Usage =
    {
        "Usage: AirKeyboard.exe [--console | --set-password | --clear-password | --print-pairing]",
        "  (none)            run in the notification area (tray)",
        @"  --console         run the server in this console (logs in logs\ under the current folder)",
        "  --set-password    set this PC's password (asks twice, or reads one line from standard input)",
        "  --clear-password  remove the password",
        "  --print-pairing   print each pairing address and its QR code"
    };

    /// <summary>
    /// No arguments: the tray app. --console: the console server (logs in logs\ under the current
    /// folder). --set-password, --clear-password, --print-pairing: one-shot commands.
    /// --setup-firewall: run elevated by the tray to add the firewall rules.
    /// Any other argument: the usage on the console and exit code 2 (no tray).
    /// </summary>
    [STAThread]
    static int Main(string[] args)
    {
        string? unknown = args.FirstOrDefault(a => a != FirewallMode && !ConsoleModes.Contains(a));
        if (unknown != null)
        {
            AttachConsole();
            Console.Error.WriteLine($"Unknown option: {unknown}");
            foreach (string line in Usage) Console.Error.WriteLine(line);
            return 2;
        }
        if (args.Contains(FirewallMode))
        {
            return FirewallSetup.AddRules();
        }
        if (args.Length == 0)
        {
            return TrayApp.Run();
        }

        AttachConsole();
        // The QR blocks and non-ASCII PC or adapter names need UTF-8
        try
        {
            Console.OutputEncoding = Encoding.UTF8;
        }
        catch (IOException)
        {
            // No console at all (a pipe from a process that has none): write UTF-8 anyway
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true });
        }
        return RunConsole(args).GetAwaiter().GetResult();
    }

    static async Task<int> RunConsole(string[] args)
    {
        // Before the logs directory or any socket, so it can run next to a live server
        if (args.Contains("--print-pairing"))
        {
            return PrintPairingCodes();
        }

        // AirKeyboard.exe --set-password / --clear-password, then restart the server
        if (args.Contains("--set-password"))
        {
            return SetPassword();
        }
        if (args.Contains("--clear-password"))
        {
            return Save(new AuthConfig(), "Password cleared. Restart the server.");
        }

        // Ensure logs directory exists
        string logsDir = "logs";
        if (!Directory.Exists(logsDir))
        {
            Directory.CreateDirectory(logsDir);
        }

        // A config file that can't be read must never mean "no password"
        AuthConfig auth;
        try
        {
            auth = AuthConfig.Load();
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Error: {ex.Message}");
            return 1;
        }

        Console.WriteLine("=== AirKeyboard ===");
        Console.WriteLine($"Started at {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        Console.WriteLine();

        // Get server name from environment or use default
        string serverName = Environment.GetEnvironmentVariable("AIRKEYBOARD_NAME") ?? Environment.MachineName;

        // Create and start the server
        // Protocol: chunk binary format with XOR encryption
        // TCP Port: 1978 (handshake + control)
        // UDP Port: 2008 (discovery)
        var server = new RemoteControlServer(serverName, auth);

        // Ctrl+C ends the process without running the finally below; never leave a button held
        Console.CancelKeyPress += (_, _) => server.ReleaseHeldButtons();
        AppDomain.CurrentDomain.ProcessExit += (_, _) => server.ReleaseHeldButtons();

        try
        {
            // Everything before StartAsync's first await (binding the ports) has run on return
            Task serverTask = server.StartAsync();
            if (!serverTask.IsFaulted)
            {
                QRCodeGenerator.PrintPairing();
                Console.WriteLine("Server running. Press Ctrl+C to stop.");
            }
            await serverTask;
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Error: {ex.Message}");
            Console.WriteLine(ex.StackTrace);
            return 1;
        }
        finally
        {
            server.Stop();
            Console.WriteLine("Server stopped.");
        }
    }

    /// <summary>Each pairing address, its QR code, then a blank line (read by protocol-check.py)</summary>
    static int PrintPairingCodes()
    {
        var addresses = QRCodeGenerator.GetPairingAddresses();
        if (addresses.Count == 0)
        {
            Console.Error.WriteLine("No network address found");
            return 2;
        }
        foreach (var pairing in addresses)
        {
            string payload = QRCodeGenerator.PayloadFor(pairing.Address);
            Console.WriteLine(payload);
            Console.WriteLine(QRCodeGenerator.RenderText(payload));
            Console.WriteLine();
        }
        return 0;
    }

    /// <summary>
    /// Asks twice with hidden input, or reads one line when input is redirected, for scripts:
    /// "Test-pass-1234" | .\AirKeyboard.exe --set-password
    /// </summary>
    static int SetPassword()
    {
        string? password;
        if (Console.IsInputRedirected)
        {
            password = Console.In.ReadLine();
            if (password == null)
            {
                Console.Error.WriteLine("No password given on standard input.");
                return 1;
            }
            // Windows PowerShell 5.1 pipes a UTF-8 BOM ahead of the text; it is not part of the password
            password = password.TrimStart('﻿');
        }
        else
        {
            Console.WriteLine($"Choose a password for this PC ({AuthConfig.MinPasswordChars} to {AuthConfig.MaxPasswordChars} characters; 12 or more is better).");
            Console.WriteLine("The app asks for it once when it connects.");
            password = ReadHidden("New password: ");
            if (ReadHidden("Type it again: ") != password)
            {
                Console.Error.WriteLine("The passwords don't match. Nothing was changed.");
                return 1;
            }
        }

        var config = new AuthConfig();
        try
        {
            config.SetPassword(password);
        }
        catch (ArgumentException ex)
        {
            Console.Error.WriteLine($"{ex.Message} Nothing was changed.");
            return 1;
        }
        return Save(config, "Password set. Restart the server.");
    }

    static int Save(AuthConfig config, string done)
    {
        try
        {
            config.Save();
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Could not write {AuthConfig.FilePath}: {ex.Message}");
            return 1;
        }
        Console.WriteLine(done);
        return 0;
    }

    private const int StdInput = -10, StdOutput = -11, StdError = -12;

    /// <summary>
    /// A WinExe starts without a console. Handles a script redirected (pipe or file) already work
    /// and are kept; the others are pointed at the parent's console, or at a new one when the
    /// parent has none (started from Explorer). Runs before anything touches Console.
    /// </summary>
    static void AttachConsole()
    {
        bool inOk = IsRedirected(StdInput), outOk = IsRedirected(StdOutput), errOk = IsRedirected(StdError);
        if (inOk && outOk && errOk) return;
        if (!AttachConsole(-1)) // ATTACH_PARENT_PROCESS
        {
            if (!AllocConsole()) return;
            Console.Title = "AirKeyboard";
        }
        if (!inOk) SetStdHandle(StdInput, OpenConsole("CONIN$"));
        if (!outOk) SetStdHandle(StdOutput, OpenConsole("CONOUT$"));
        if (!errOk) SetStdHandle(StdError, OpenConsole("CONOUT$"));
    }

    /// <summary>A pipe or disk file (FILE_TYPE_PIPE, FILE_TYPE_DISK); not a console or nothing</summary>
    static bool IsRedirected(int std)
    {
        IntPtr h = GetStdHandle(std);
        if (h == IntPtr.Zero || h == new IntPtr(-1)) return false;
        uint type = GetFileType(h);
        return type == 1 || type == 3;
    }

    static IntPtr OpenConsole(string name) =>
        // GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, OPEN_EXISTING
        CreateFile(name, 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetStdHandle(int std);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetStdHandle(int std, IntPtr handle);
    [DllImport("kernel32.dll")]
    private static extern uint GetFileType(IntPtr handle);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr security,
        uint creation, uint flags, IntPtr template);

    /// <summary>Reads a line from the console, echoing '*' for each character</summary>
    static string ReadHidden(string prompt)
    {
        Console.Write(prompt);
        var text = new StringBuilder();
        while (true)
        {
            ConsoleKeyInfo key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter) break;
            if (key.Key == ConsoleKey.Backspace)
            {
                if (text.Length > 0)
                {
                    text.Length--;
                    Console.Write("\b \b");
                }
                continue;
            }
            if (char.IsControl(key.KeyChar)) continue;
            text.Append(key.KeyChar);
            Console.Write('*');
        }
        Console.WriteLine();
        return text.ToString();
    }
}
