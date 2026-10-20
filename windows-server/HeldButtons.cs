using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading;

namespace AirKeyboard
{
    /// <summary>
    /// Mouse buttons held down by a client ("mos p b" until "mos r b"), so a drag can never
    /// leave a button stuck: each held button remembers the address that pressed it and when
    /// that address last sent a "mos" command. A button is released by the client's "r", when
    /// that address's session ends, when the server stops, or after 5 s with no input from it
    /// (the app sends a zero move every second while a drag is held still).
    /// </summary>
    public class HeldButtons
    {
        private const long TIMEOUT_MS = 5000;

        private readonly InputController _input;
        private readonly ActivityLogger _logger;
        private readonly object _lock = new();
        private readonly Dictionary<MouseButton, (IPAddress owner, long lastSeen)> _held = new();
        private readonly System.Threading.Timer _timer;

        public HeldButtons(InputController input, ActivityLogger logger)
        {
            _input = input;
            _logger = logger;
            _timer = new System.Threading.Timer(_ => ReleaseIdle(), null, 1000, 1000);
        }

        /// <summary>Button down; a press of a button already held is not injected again
        /// (a duplicate would turn the drag into a double-click)</summary>
        public void Press(MouseButton button, IPAddress peer)
        {
            lock (_lock)
            {
                if (!_held.ContainsKey(button)) _input.ButtonDown(button);
                _held[button] = (peer, Environment.TickCount64);
            }
        }

        /// <summary>Button up if held; otherwise nothing, so the app's repeated releases add no events</summary>
        public void Release(MouseButton button)
        {
            lock (_lock)
            {
                if (_held.Remove(button)) _input.ButtonUp(button);
            }
        }

        /// <summary>Any "mos" command from the owner keeps its buttons held</summary>
        public void Touch(IPAddress peer)
        {
            lock (_lock)
            {
                long now = Environment.TickCount64;
                foreach (var button in _held.Keys.ToList())
                {
                    if (_held[button].owner.Equals(peer)) _held[button] = (peer, now);
                }
            }
        }

        public void ReleaseOwnedBy(IPAddress peer, string reason) =>
            ReleaseWhere(h => h.owner.Equals(peer), reason);

        public void ReleaseAll(string reason) => ReleaseWhere(_ => true, reason);

        private void ReleaseIdle()
        {
            long now = Environment.TickCount64;
            ReleaseWhere(h => now - h.lastSeen > TIMEOUT_MS, "no input for 5 s");
        }

        private void ReleaseWhere(Func<(IPAddress owner, long lastSeen), bool> match, string reason)
        {
            lock (_lock)
            {
                foreach (var button in _held.Keys.ToList())
                {
                    if (!match(_held[button])) continue;
                    _held.Remove(button);
                    try
                    {
                        _input.ButtonUp(button);
                        _logger.Log($"[held] {button} released: {reason}");
                    }
                    catch (Exception ex)
                    {
                        _logger.Log($"[held] {button} release failed: {ex.Message}");
                    }
                }
            }
        }
    }
}
