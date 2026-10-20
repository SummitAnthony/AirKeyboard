"""Checks a stress-capture.ps1 report from the Android StressTest.

Usage: python -I verify-stress.py report.json [--log SERVER_LOG] [--latency SEND_FILE]
Recomputes the text the tablet should have produced and compares it with the report's
"text" field, then pairs the recorded left-button presses and releases: they must strictly
alternate, with exactly 51 clicks (moved < 5 px), exactly 17 drags (moved >= 50 px) and
nothing in between, and no button may still be held at the end. Exits 1 on any FAIL.

--log      the server log: FAIL on any frame dropped for a bad tag or a TCP replay, on any
           button the server had to release because input stopped for 5 s, and when no
           live-typing [BACKSPACE] came over the TCP session (only the lines written while
           this capture ran are read). UDP replay drops are only counted (WARN): the
           tablet sends each frame once with a fresh id, so they are datagrams the network
           duplicated, rightly dropped; a real frame lost that way fails the exact text
           and click counts above.
--latency  the T5 send log pulled from the tablet: one line per typed letter with its
           path (tcp/udp), the letter and the tablet's send time in Unix ms, e.g.
           "tcp a 1792145590867". Pairs each send with its arrival in the capture and
           reports, per path, received, lost, reordered, median and p95 of arrival - send.
           The clock offset between the two devices cancels in the TCP minus UDP median,
           which must be <= 15 ms, with no TCP letter lost or reordered.
"""
import argparse
import json
import re
import sys

P1 = "Paste test: Wörld 😀 漢字\nsecond line\n\tthird line"
P2 = "".join(f"{i:05d} The quick brown fox jumps over the lazy dog 0123456789 äöü €\n" for i in range(2000))
T3 = "".join(f"[{i:03d}]abcdefghijklmnopqrstuvwxyzÄÖÜ" for i in range(100))
T4 = "".join(f"<{i:02d}>liveÄö😀 " for i in range(100))
# Exact: a long press lifted without moving (b2 does 3) must not add a press
CLICKS = 1 + 50  # the first trackpad tap plus the StressTest's 50 sendMouseClick
DRAGS = 10 + 5 + 1 + 1  # b2: 10 lifted, 5 cancelled, 1 held still 7 s; f0: 1 ended by the churn
CLICK_MAX_DX = 5
DRAG_MIN_DX = 50
MAX_TCP_EXTRA_MS = 15

EVENT = re.compile(r"^(down|up) (\w+) @(-?\d+),(-?\d+)$")
LOG_TIME = re.compile(r"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\] ")
BAD_FRAME = re.compile(r"\bbad tag\b|\[TCP [^\]]+\] replay\b")
UDP_REPLAY = re.compile(r"UDP dropped from \S+: replay\b")
TIMEOUT_RELEASE = re.compile(r"\[held\].*no input for 5 s")
TCP_BACKSPACE = re.compile(r"\[TCP [^\]]+\] key \[BACKSPACE\]")


def norm_crlf(s: str) -> str:
    """Lone \\n and lone \\r become \\r\\n; an existing \\r\\n stays as it is."""
    return re.sub(r"\r\n|\r|\n", "\r\n", s)


def expected_text() -> str:
    return norm_crlf(P1) + "\r\n" + norm_crlf(P2) + T3 + T4


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


def inserted(want: str, got: str):
    """The text inserted into want to give got (one contiguous insertion), else None"""
    k = len(got) - len(want)
    if k <= 0:
        return None
    d = first_diff(want, got)
    s = 0
    while s < len(want) and want[-1 - s] == got[-1 - s]:
        s += 1
    p = len(want) - s
    return got[p:p + k] if p <= d else None


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


def read_sends(path):
    """[(path, letter, send ms)] in file order; lines without all three are skipped"""
    sends, skipped = [], 0
    with open(path, encoding="utf-8-sig", errors="replace") as f:
        for line in f:
            tokens = [t for t in re.split(r"[^0-9A-Za-z_.]+", line) if t]
            words = [t.lower() for t in tokens]
            kind = next(({"tcp": "tcp", "true": "tcp", "udp": "udp", "false": "udp"}[w]
                         for w in words if w in ("tcp", "udp", "true", "false")), None)
            if kind is None:
                kind = next(({"T": "tcp", "U": "udp"}[t] for t in tokens if t in ("T", "U")), None)
            letters = [t for t in tokens if len(t) == 1 and "a" <= t <= "z"]
            times = [t for t in tokens if re.fullmatch(r"\d{12,14}(\.\d+)?", t)]
            if kind and len(letters) == 1 and len(times) == 1:
                sends.append((kind, letters[0], float(times[0])))
            elif line.strip():
                skipped += 1
    return sends, skipped


def align(sent: str, got: str):
    """Best local alignment of sent inside got (Smith-Waterman): [(i, j)] of matched letters"""
    MATCH, MISMATCH, GAP = 2, -2, -1
    n, m = len(sent), len(got)
    prev = [0] * (m + 1)
    trace = [bytearray(m + 1)]
    best, bi, bj = 0, 0, 0
    for i in range(1, n + 1):
        row, tr, c = [0] * (m + 1), bytearray(m + 1), sent[i - 1]
        for j in range(1, m + 1):
            diag = prev[j - 1] + (MATCH if got[j - 1] == c else MISMATCH)
            up = prev[j] + GAP
            left = row[j - 1] + GAP
            h = max(0, diag, up, left)
            if h:
                row[j] = h
                tr[j] = 1 if h == diag else 2 if h == up else 3
                if h > best:
                    best, bi, bj = h, i, j
        prev = row
        trace.append(tr)
    pairs, i, j = [], bi, bj
    while i > 0 and j > 0 and trace[i][j]:
        step = trace[i][j]
        if step == 1:
            if sent[i - 1] == got[j - 1]:
                pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif step == 2:
            i -= 1
        else:
            j -= 1
    return pairs[::-1]


def percentile(values, q):
    s = sorted(values)
    return s[min(len(s) - 1, max(0, round(q * (len(s) - 1))))]


def check_latency(path, report):
    """Returns (failed, letters received) for the T5 latency check"""
    sends, skipped = read_sends(path)
    if skipped:
        print(f"INFO  latency: {skipped} lines of {path} skipped (no path, letter and time)")
    arrivals = []
    for a in as_list(report.get("arrivals")):
        code, ms = a.split(" ")
        arrivals.append((chr(int(code)), float(ms)))
    if not sends or not arrivals:
        print(f"FAIL  latency: {len(sends)} sends, {len(arrivals)} arrivals in the report")
        return True, 0
    sent = "".join(s[1] for s in sends)
    got = "".join(a[0] for a in arrivals)
    pairs = align(sent, got)
    if len(pairs) < len(sends) // 2:
        print(f"FAIL  latency: the sent letters were not found in the capture ({len(pairs)} of {len(sends)} matched)")
        return True, len(pairs)

    # Letters the alignment left out may have arrived out of order: pair each with an unused
    # arrival of the same letter near where it should have been
    aligned = dict(pairs)
    match = dict(pairs)
    used = set(match.values())
    lo, hi = pairs[0][1] - 20, pairs[-1][1] + 20
    reordered = set()
    last = pairs[0]
    for i in range(len(sends)):
        if i in aligned:
            last = (i, aligned[i])
            continue
        want = last[1] + (i - last[0])
        near = [j for j in range(max(lo, want - 20), min(hi, want + 20) + 1)
                if 0 <= j < len(got) and j not in used and got[j] == sent[i]]
        if near:
            j = min(near, key=lambda j: abs(j - want))
            match[i] = j
            used.add(j)
            before = max((aligned[k] for k in aligned if k < i), default=-1)
            after = min((aligned[k] for k in aligned if k > i), default=len(got))
            if not before < j < after:
                reordered.add(i)

    failed = False
    medians = {}
    for kind in ("tcp", "udp"):
        idx = [i for i, s in enumerate(sends) if s[0] == kind]
        got_idx = [i for i in idx if i in match]
        lat = [arrivals[match[i]][1] - sends[i][2] for i in got_idx]
        lost = len(idx) - len(got_idx)
        order = sum(1 for i in idx if i in reordered)
        if lat:
            medians[kind] = percentile(lat, 0.5)
            print(f"INFO  latency {kind.upper()}: {len(idx)} sent, {len(got_idx)} received, {lost} lost, "
                  f"{order} reordered; arrival - send median {medians[kind]:.1f} ms, "
                  f"p95 {percentile(lat, 0.95):.1f} ms (includes the clock offset)")
        else:
            print(f"INFO  latency {kind.upper()}: {len(idx)} sent, 0 received")
        if kind == "tcp":
            if idx and lost == 0 and order == 0:
                print(f"PASS  latency TCP: all {len(idx)} letters arrived, in order")
            else:
                failed = True
                print(f"FAIL  latency TCP: {len(idx)} sent, {lost} lost, {order} reordered")
    if "tcp" in medians and "udp" in medians:
        extra = medians["tcp"] - medians["udp"]
        ok = extra <= MAX_TCP_EXTRA_MS
        failed |= not ok
        print(f"{'PASS' if ok else 'FAIL'}  latency: TCP median - UDP median = {extra:.1f} ms "
              f"({'<=' if ok else '>'} {MAX_TCP_EXTRA_MS})")
    else:
        failed = True
        print("FAIL  latency: needs letters received on both paths")
    return failed, len(match)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report")
    ap.add_argument("--log", help="server log file")
    ap.add_argument("--latency", metavar="SEND_FILE", help="T5 send log from the tablet")
    args = ap.parse_args()
    with open(args.report, encoding="utf-8-sig") as f:  # PowerShell's Set-Content -Encoding UTF8 writes a BOM
        report = json.load(f)

    failed = False
    t5_received = None
    if args.latency:
        lat_failed, t5_received = check_latency(args.latency, report)
        failed |= lat_failed

    want = expected_text()
    got = report.get("text") or ""
    extra = None if got == want else inserted(want, got)
    if got == want:
        print(f"PASS  text: {len(got)} chars match")
    elif extra is not None and re.fullmatch(r"[a-z]+", extra) and t5_received == len(extra):
        print(f"PASS  text: {len(want)} chars match, plus the {len(extra)} latency-test letters")
    else:
        failed = True
        if extra is not None and re.fullmatch(r"[a-z]+", extra):
            print(f"FAIL  text: {len(extra)} extra letters inserted"
                  + (" (the latency test's? pass --latency)" if t5_received is None
                     else f", but {t5_received} latency-test letters arrived"))
        else:
            at = first_diff(want, got)
            print(f"FAIL  text: first difference at offset {at} (expected {len(want)} chars, got {len(got)})")
            print(f"      expected: {want[max(0, at - 60):at + 60]!a}")
            print(f"      got     : {got[max(0, at - 60):at + 60]!a}")

    events = as_list(report.get("events"))
    counts = {name: sum(1 for e in events if e.startswith(name + " "))
              for name in ("down Left", "down Right", "down Middle")}
    wheels = sum(1 for e in events if e.startswith("wheel "))
    keys = sum(1 for e in events if e.startswith("key "))
    print(f"INFO  events: {len(events)} stored, {report.get('dropped', 0)} dropped; "
          f"left {counts['down Left']}, right {counts['down Right']}, middle {counts['down Middle']}, "
          f"wheel {wheels}, key {keys}")

    pairs, error = button_pairs(events, "Left")
    if error:
        failed = True
        print(f"FAIL  left press/release alternate: {error}")
    else:
        print(f"PASS  left press/release alternate: {len(pairs)} pairs")
    clicks = sum(1 for d, u in pairs if abs(u - d) < CLICK_MAX_DX)
    drags = sum(1 for d, u in pairs if abs(u - d) >= DRAG_MIN_DX)
    other = len(pairs) - clicks - drags
    for name, n, need in (("left clicks", clicks, CLICKS), ("left drags", drags, DRAGS),
                          (f"left pairs moved {CLICK_MAX_DX}-{DRAG_MIN_DX - 1} px", other, 0)):
        if n == need:
            print(f"PASS  {name}: {n}")
        else:
            failed = True
            print(f"FAIL  {name}: {n} (need exactly {need})")

    buttons = report.get("buttons")
    if buttons == "None":
        print("PASS  no button held at the end")
    else:
        failed = True
        print(f"FAIL  buttons held at the end: {buttons!r}")

    if args.log:
        lines = log_lines(args.log, report)
        replays = [line for line in lines if UDP_REPLAY.search(line)]
        if replays:
            print(f"WARN  UDP frames dropped as a replay (network duplicates): {len(replays)}, first: {replays[0]!a}")
        for name, pattern in (("frames dropped for a bad tag or a TCP replay", BAD_FRAME),
                              ("buttons released by the 5 s timeout", TIMEOUT_RELEASE)):
            hits = [line for line in lines if pattern.search(line)]
            if hits:
                failed = True
                print(f"FAIL  {name}: {len(hits)}, first: {hits[0]!a}")
            else:
                print(f"PASS  {name}: none")
        n = sum(1 for line in lines if TCP_BACKSPACE.search(line))
        if n:
            print(f"PASS  live Backspace went over the TCP session: {n} lines")
        else:
            failed = True
            print("FAIL  live Backspace went over the TCP session: no '[TCP ...] key [BACKSPACE]' line")

    print("FAIL" if failed else "PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
