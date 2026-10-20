using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;

namespace AirKeyboard
{
    /// <summary>
    /// Per-IP brute-force limit for rmauth1 logins (in memory, reset on restart).
    /// 5 consecutive failed or malformed ath -> locked 30 s; each further failure doubles the
    /// lock up to 10 min; a success resets the IP. An IP's count and lock level are kept until it
    /// has been quiet for 1 hour, so waiting out a 10-min lock doesn't reset the escalation.
    /// There is no global bucket, so one host can't lock everyone out. Only failures count;
    /// while an IP is locked, its ath is not evaluated. The table holds at most MaxEntries IPs.
    /// </summary>
    public class AuthLimiter
    {
        public const int MaxFailures = 5;
        private const int FirstLockSeconds = 30;
        private const int MaxLockSeconds = 600;
        // An IP's history is forgotten this long after its last failure or the end of its lock
        private const long ForgetAfterMs = 60 * 60 * 1000L;
        private const long PruneEveryMs = 60_000;
        // Many addresses failing within the hour (IPv6 makes them cheap) can't grow it past this
        private const int MaxEntries = 4096;

        private class Entry
        {
            public int Failures;
            public int LockSeconds;
            public long LockedUntil;   // Environment.TickCount64
            public long LastFailure;
        }

        private readonly object _lock = new();
        private readonly Dictionary<IPAddress, Entry> _entries = new();
        private long _lastPrune = Environment.TickCount64;

        /// <summary>True while ip is locked out; seconds = whole seconds left (at least 1).</summary>
        public bool IsLocked(IPAddress ip, out int seconds)
        {
            lock (_lock)
            {
                long now = Environment.TickCount64;
                Prune(now);
                seconds = 0;
                if (!_entries.TryGetValue(Normalize(ip), out Entry? e) || e.LockedUntil <= now) return false;
                seconds = (int)Math.Max(1, (e.LockedUntil - now + 999) / 1000);
                return true;
            }
        }

        /// <summary>Records a failed or malformed ath. Returns the consecutive failure count
        /// since the last success (it keeps counting past MaxFailures); call IsLocked afterwards
        /// to learn whether this failure started a lock.</summary>
        public int Fail(IPAddress ip)
        {
            lock (_lock)
            {
                long now = Environment.TickCount64;
                Prune(now);
                ip = Normalize(ip);
                if (!_entries.TryGetValue(ip, out Entry? e))
                {
                    if (_entries.Count >= MaxEntries) EvictQuietest(now);
                    e = new Entry();
                    _entries[ip] = e;
                }
                e.Failures++;
                e.LastFailure = now;
                if (e.Failures >= MaxFailures)
                {
                    e.LockSeconds = e.LockSeconds == 0 ? FirstLockSeconds : Math.Min(e.LockSeconds * 2, MaxLockSeconds);
                    e.LockedUntil = now + e.LockSeconds * 1000L;
                }
                return e.Failures;
            }
        }

        /// <summary>A correct login: forget the IP's failures and lock history.</summary>
        public void Success(IPAddress ip)
        {
            lock (_lock)
            {
                _entries.Remove(Normalize(ip));
            }
        }

        private static IPAddress Normalize(IPAddress ip) => ip.IsIPv4MappedToIPv6 ? ip.MapToIPv4() : ip;

        /// <summary>
        /// The table is full: drops the IP that has been quiet longest, preferring one that is not
        /// locked right now (a locked IP is the one worth keeping).
        /// </summary>
        private void EvictQuietest(long now)
        {
            IPAddress quietest = _entries
                .OrderBy(kv => kv.Value.LockedUntil > now ? 1 : 0)
                .ThenBy(kv => Math.Max(kv.Value.LockedUntil, kv.Value.LastFailure))
                .First().Key;
            _entries.Remove(quietest);
        }

        // Drops IPs that are unlocked and quiet, so the table can't grow without bound
        private void Prune(long now)
        {
            if (now - _lastPrune < PruneEveryMs) return;
            _lastPrune = now;
            foreach (IPAddress ip in _entries
                         .Where(kv => now - Math.Max(kv.Value.LockedUntil, kv.Value.LastFailure) >= ForgetAfterMs)
                         .Select(kv => kv.Key).ToList())
            {
                _entries.Remove(ip);
            }
        }
    }
}
