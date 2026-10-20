"""Checks an e2e-capture.ps1 report from the Android EndToEndTest.

Usage: python -I verify-e2e.py report.json [--log SERVER_LOG]
Compares the report's "text" with what the test types (Send mode, then live typing), checks
that every expected click, wheel and key event was recorded, and pairs the left-button
presses and releases: they must strictly alternate, with exactly 3 clicks (moved < 5 px),
exactly 3 drags (moved >= 50 px) and nothing in between, and no button may still be held at
the end. Exits 1 on any FAIL. The counts are for fullSession alone (run it with
-e class com.airkeyboard.EndToEndTest#fullSession when a password test would also run).

--log  the server log (only the lines written while this capture ran are read): live
       typing must have used the TCP session ([SHIFT+Q] and at least 3 [BACKSPACE] from
       "TCP ..."), the typed text must never appear, and the server must not have had to
       release a button itself (every drag in this test is ended by the app).
"""
import argparse
import json
import re
import sys

SEND_MODE = "Hello Wörld 123\r\nab\t"
LIVE = "live the quick 😀Wörld 漢字\r\nnexQ\r\n"
EXPECTED_TEXT = SEND_MODE + LIVE
AT_LEAST = {"down Left": 1, "down Right": 1, "down Middle": 1, "wheel": 1, "key Tab": 1, "key Escape": 1,
            "key A, Control": 1, "key C, Control": 1, "key VolumeUp": 1, "key VolumeDown": 1,
            "key Q, Shift": 1, "key Return": 3, "key Back": 4}
# Exact: a long press lifted without moving, or a slow swipe, must not add a press
CLICKS = 3  # the first trackpad tap and two btnLeftClick
DRAGS = 3  # +120 lifted, -120 cancelled, +60 ended by leaving the trackpad
CLICK_MAX_DX = 5
DRAG_MIN_DX = 50
NEVER_LOGGED = "Wörld 漢字"

EVENT = re.compile(r"^(down|up) (\w+) @(-?\d+),(-?\d+)$")
LOG_TIME = re.compile(r"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\] ")
# Combos log only key names; a letter shows as "?"
TCP_SHIFT_Q = re.compile(r"\[TCP [^\]]+\] key \[SHIFT\+(Q|\?)\]")
TCP_BACKSPACE = re.compile(r"\[TCP [^\]]+\] key \[BACKSPACE\]")
SERVER_RELEASE = re.compile(r"\[held\].*(session ended|no input for 5 s)")


def first_diff(a: str, b: str) -> int:
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def as_list(value):
    """ConvertTo-Json writes a one-element list as a bare string"""
    if not value:
        return []
    return [value] if isinstance(value, str) else value


def button_pairs(events, button):
    """(pairs as (down x, up x), error or None) for one button's down/up events"""
    pairs, down = [], None
    for n, (kind, x) in enumerate((m[1], int(m[3])) for m in map(EVENT.match, events) if m and m[2] == button):
        if (kind == "down") == (down is not None):
            return pairs, f"{button} event {n} is a second '{kind}' in a row"
        if kind == "down":
            down = x
        else:
            pairs.append((down, x))
            down = None
    return pairs, (f"{button} still down after the last event" if down is not None else None)


def log_lines(path, report):
    """The server log lines written while the capture ran"""
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    start, end = report.get("startedAt"), report.get("writtenAt")
    if not start or not end:
        print("INFO  log: the report has no startedAt/writtenAt; checking the whole log")
        return lines
    picked, inside = [], False
    for line in lines:
        m = LOG_TIME.match(line)
        if m:
            inside = start <= m[1] <= end
        if inside:
            picked.append(line)
    print(f"INFO  log: {len(picked)} lines from {start} to {end}")
    return picked


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report")
    ap.add_argument("--log", help="server log file")
    args = ap.parse_args()
    with open(args.report, encoding="utf-8-sig") as f:  # PowerShell's Set-Content -Encoding UTF8 writes a BOM
        report = json.load(f)

    failed = False

    def check(name, ok, detail=""):
        nonlocal failed
        failed |= not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f": {detail}" if detail else ""))

    got = report.get("text") or ""
    if got == EXPECTED_TEXT:
        check("text", True, f"{len(got)} chars match")
    else:
        at = first_diff(EXPECTED_TEXT, got)
        check("text", False, f"first difference at offset {at} (expected {len(EXPECTED_TEXT)} chars, got {len(got)})")
        print(f"      expected: {EXPECTED_TEXT[max(0, at - 30):at + 30]!a}")
        print(f"      got     : {got[max(0, at - 30):at + 30]!a}")

    events = as_list(report.get("events"))
    print(f"INFO  events: {len(events)} stored")
    for name, need in AT_LEAST.items():
        n = sum(1 for e in events if e.startswith(name + " "))
        check(f"'{name}' events", n >= need, f"{n}" + (f" (need {need})" if n < need or need > 1 else ""))

    pairs, error = button_pairs(events, "Left")
    check("left press/release alternate", error is None, error or f"{len(pairs)} pairs")
    clicks = sum(1 for d, u in pairs if abs(u - d) < CLICK_MAX_DX)
    drags = sum(1 for d, u in pairs if abs(u - d) >= DRAG_MIN_DX)
    other = len(pairs) - clicks - drags
    check("left clicks", clicks == CLICKS, f"{clicks} (need exactly {CLICKS})")
    check("left drags", drags == DRAGS, f"{drags} (need exactly {DRAGS})")
    check(f"no left pairs moved {CLICK_MAX_DX}-{DRAG_MIN_DX - 1} px", other == 0, f"{other}" if other else "")
    buttons = report.get("buttons")
    check("no button held at the end", buttons == "None", "" if buttons == "None" else f"buttons {buttons!r}")

    if args.log:
        lines = log_lines(args.log, report)
        n = sum(1 for line in lines if TCP_SHIFT_Q.search(line))
        check("live Shift+Q went over the TCP session", n >= 1, f"{n} lines")
        n = sum(1 for line in lines if TCP_BACKSPACE.search(line))
        check("live Backspace went over the TCP session", n >= 3, f"{n} lines (need 3)")
        n = sum(1 for line in lines if NEVER_LOGGED in line)
        check("typed text never logged", n == 0, f"{n} lines contain it" if n else "")
        hits = [line for line in lines if SERVER_RELEASE.search(line)]
        check("no button released by the server", not hits, f"{len(hits)}, first: {hits[0]!a}" if hits else "")

    print("FAIL" if failed else "PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
