using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;

namespace AirKeyboard
{
    /// <summary>
    /// Logs server activity to file and console
    /// </summary>
    public class ActivityLogger
    {
        /// <summary>Folder for new loggers: "logs" under the current folder (console mode);
        /// the tray sets %LOCALAPPDATA%\AirKeyboard\logs</summary>
        public static string LogsDir { get; set; } = "logs";

        private readonly string _logsDir = LogsDir;
        private readonly object _lockObject = new();

        // One file per day (picked on each write, so a long-running server rolls over), each
        // capped in size; files older than KeepDays are deleted at startup and at each new day
        private const long MaxFileBytes = 50L * 1024 * 1024;
        private const int KeepDays = 14;
        private const int MaxMessageChars = 1024;
        private string _logFile = "";
        private string _logDate = "";
        private long _fileBytes;
        private bool _capReached;

        // LogLimited: at most LimitedPerWindow lines per kind in each LimitWindow
        private const int LimitedPerWindow = 20;
        private static readonly TimeSpan LimitWindow = TimeSpan.FromSeconds(10);
        private readonly Dictionary<string, (DateTime start, int count, int suppressed)> _limited = new();

        public ActivityLogger()
        {
            if (!Directory.Exists(_logsDir))
            {
                Directory.CreateDirectory(_logsDir);
            }

            Log("=== Server Started ===");
        }

        public void Log(string message)
        {
            lock (_lockObject)
            {
                try
                {
                    DateTime now = DateTime.Now;
                    string timestamp = now.ToString("yyyy-MM-dd HH:mm:ss.fff");
                    string logEntry = $"[{timestamp}] {Sanitize(message)}";

                    // Write to console
                    Console.WriteLine(logEntry);

                    // Write to file
                    WriteToFile(now, timestamp, logEntry);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error writing to log: {ex.Message}");
                }
            }
        }

        /// <summary>
        /// For messages anyone on the network can trigger at will (dropped frames, refusals):
        /// at most 20 lines per kind every 10 s, then one line saying how many were left out
        /// </summary>
        public void LogLimited(string kind, string message)
        {
            lock (_lockObject)
            {
                DateTime now = DateTime.UtcNow;
                if (!_limited.TryGetValue(kind, out var state) || now - state.start >= LimitWindow)
                {
                    if (state.suppressed > 0)
                    {
                        Log($"({state.suppressed} more '{kind}' messages were suppressed)");
                    }
                    state = (now, 0, 0);
                }

                if (state.count < LimitedPerWindow)
                {
                    state.count++;
                    Log(message);
                }
                else
                {
                    state.suppressed++;
                }
                _limited[kind] = state;
            }
        }

        private void WriteToFile(DateTime now, string timestamp, string logEntry)
        {
            string date = now.ToString("yyyy-MM-dd");
            if (date != _logDate)
            {
                _logDate = date;
                _logFile = Path.Combine(_logsDir, $"server-{date}.log");
                _fileBytes = File.Exists(_logFile) ? new FileInfo(_logFile).Length : 0;
                _capReached = false;
                DeleteOldLogs(now);
            }

            if (_capReached) return;

            string line = logEntry + Environment.NewLine;
            if (_fileBytes >= MaxFileBytes)
            {
                line = $"[{timestamp}] Log file size limit ({MaxFileBytes / (1024 * 1024)} MB) reached; " +
                       "further lines today go to the console only" + Environment.NewLine;
                _capReached = true;
            }

            File.AppendAllText(_logFile, line);
            _fileBytes += Encoding.UTF8.GetByteCount(line);
        }

        private void DeleteOldLogs(DateTime now)
        {
            try
            {
                foreach (string file in Directory.GetFiles(_logsDir, "server-*.log"))
                {
                    try
                    {
                        if (File.GetLastWriteTime(file) < now.AddDays(-KeepDays)) File.Delete(file);
                    }
                    catch { }
                }
            }
            catch { }
        }

        /// <summary>
        /// Caps the length, and replaces control characters so text from the network (CR/LF
        /// above all) can never forge or break up log lines
        /// </summary>
        private static string Sanitize(string message)
        {
            if (message.Length > MaxMessageChars) message = message.Substring(0, MaxMessageChars) + "...";

            StringBuilder? sb = null;
            for (int i = 0; i < message.Length; i++)
            {
                if (char.IsControl(message[i]))
                {
                    sb ??= new StringBuilder(message);
                    sb[i] = '?';
                }
            }
            return sb?.ToString() ?? message;
        }

        public void LogConnection(string clientAddress, string action)
        {
            Log($"[CONNECTION] {clientAddress} - {action}");
        }

        public void LogCommand(string clientAddress, string command)
        {
            Log($"[COMMAND] {clientAddress} - {command}");
        }

        public void LogError(string message, Exception ex)
        {
            Log($"[ERROR] {message}: {ex.Message}");
        }
    }
}
