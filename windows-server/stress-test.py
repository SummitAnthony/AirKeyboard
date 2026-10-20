"""Protocol stress and fuzz test against a RUNNING server.

Usage: python -I stress-test.py [host] [--paste] [--capture PATH] [--log PATH] [--pid PID] [--seed N]
       python -I stress-test.py [host] --password PW --log PATH [--paste] [--pid PID]

Safe to run on a PC in use: the only input-producing frames it sends are "mos s 0" (scroll
zero notches) and "mos m 0 0" (move zero pixels), plus, when --paste is given, clipboard
pastes ("clp", which end in Ctrl+V), typed text and left-button presses, all into the capture
window. Every fuzz frame is checked to never decrypt to a "mos", "key" or "clp" payload;
section J's "key" frames are malformed or name an unknown key, so the server rejects them
before any input.

  --paste  also run sections B and H: sets this PC's clipboard and makes the server press
           Ctrl+V and type into the focused window, and holds the left button down in the
           middle of that window (H needs the server on this PC). Each step waits until the
           window titled "AirKeyboard stress capture" (stress-capture.ps1) is in front.
  --capture  that window's report file (its -Out), to check each paste reached the window.
  --log    the server's log file (e.g. logs\\server-YYYY-MM-DD.log next to where the server
           was started) to measure UDP loss, confirm each paste's Ctrl+V was sent, and check
           the log never holds pasted text.
  --pid    the server's process id, to count its open TCP connections at the end.
  --password  the server's password (a TEST password only): runs only section P against a
           password-protected server, then G. Needs --log; with --paste, P17 also holds the
           left button down. P18 connects from 127.0.0.2-127.0.0.5, so it runs only when host
           is 127.0.0.1 or localhost.

Sections: A baseline, B clipboard paste and typing over TCP, H held buttons (needs --paste),
C UDP flood, I commands over the TCP session, D TCP concurrency, E slowloris, F fuzz,
J log hygiene (needs --log), G leftover connections; P password (only with --password).
Exits 1 if any check FAILs.
"""
import argparse
import base64
import functools
import hashlib
import hmac
import json
import os
import random
import re
import select
import socket
import struct
import subprocess
import sys
import threading
import time
import unicodedata

PORT = 1978
DISCOVERY_PORT = 2008
HEADER_BYTE = 0x23
RESERVED = (b"mos", b"key", b"clp")
CLP_SLICE = 998
CLP_LIMIT = 1_048_576
CAPTURE_TITLE = "stress capture"
CAPTURE_WINDOW = "AirKeyboard stress capture"
MARKER = "QZX-never-log-me"  # in every pasted text; must never show up in the server log
LOOPBACK = ("127.0.0.1", "localhost", "::1")

socket.setdefaulttimeout(5)  # nothing may hang forever
results = []
chunk_id = 0


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}", flush=True)
    return ok


def info(name, detail):
    print(f"INFO  {name}: {detail}", flush=True)


# ---------------------------------------------------------------- frames (from protocol-check.py)

def encrypt_chunk(payload: bytes, cid: int = 0, rng=random) -> bytes:
    key = rng.randint(1, 255)
    frame_key = bytes(rng.randint(1, 255) for _ in range(8))
    ts = bytes(b ^ key for b in struct.pack("<d", time.time()))
    header = (
        b"\x23"
        + struct.pack("<fif", 3.0, cid, 0.0)
        + ts
        + struct.pack("<ii", rng.randint(0, 2**31 - 1), rng.randint(0, 2**31 - 1))
        + bytes([key ^ 0x35])
        + bytes(k ^ key for k in frame_key)
    )
    assert len(header) == 38
    return header + bytes(p ^ frame_key[i % 8] for i, p in enumerate(payload))


def command(kind: bytes, data: bytes) -> bytes:
    assert len(data) <= 999
    return kind + f"{len(data):03d}".encode() + data


def frame(kind: bytes, data) -> bytes:
    global chunk_id
    chunk_id += 1
    return encrypt_chunk(command(kind, data if isinstance(data, bytes) else data.encode()), chunk_id)


def decrypt(data: bytes):
    """Same steps as the server's EncryptionUtil.DecryptChunk."""
    if len(data) < 38 or data[0] != HEADER_BYTE:
        return None
    key = data[29] ^ 0x35
    frame_key = bytes(b ^ key for b in data[30:38])
    return bytes(p ^ frame_key[i % 8] for i, p in enumerate(data[38:]))


def is_safe(blob: bytes) -> bool:
    """True unless a frame starting at ANY offset would decrypt to a mos/key/clp payload."""
    i = blob.find(HEADER_BYTE)
    while i != -1 and len(blob) - i >= 41:
        if decrypt(blob[i:i + 41])[:3].lower() in RESERVED:
            return False
        i = blob.find(HEADER_BYTE, i + 1)
    return True


# Fuzz plaintext never holds control characters, so the server's log and console stay readable
_PRINTABLE = bytes(b if b >= 0x20 and b != 0x7F else 0x3F for b in range(256))


def garbage(rng, n: int) -> bytes:
    return rng.randbytes(n).translate(_PRINTABLE)


def seal(header: bytes, plain: bytes) -> bytes:
    """Encrypts plain under whatever key a (possibly garbage) 38-byte header carries."""
    key = header[29] ^ 0x35
    frame_key = bytes(b ^ key for b in header[30:38])
    return header + bytes(p ^ frame_key[i % 8] for i, p in enumerate(plain))


def fuzz_type(rng) -> bytes:
    while True:
        t = rng.choice([b"zzz", b"abc", b"MOX", b"k3y", garbage(rng, 3)])
        if t.lower() not in RESERVED:
            return t


def clp_frames(data: bytes, slice_=CLP_SLICE):
    out = [frame(b"clp", b"b")]
    out += [frame(b"clp", b"d" + data[i:i + slice_]) for i in range(0, len(data), slice_)]
    out.append(frame(b"clp", b"e"))
    return out


def clp_send(sock, data: bytes, slice_=CLP_SLICE, mode="frames"):
    """Sends one b/d.../e transfer: frame by frame, all in one sendall, or 1 byte at a time."""
    frames = clp_frames(data, slice_)
    if mode == "coalesced":
        sock.sendall(b"".join(frames))
    elif mode == "dribble":
        for b in b"".join(frames):
            sock.sendall(bytes([b]))
            time.sleep(0.001)
    else:
        for f in frames:
            sock.sendall(f)


def norm_crlf(s: str) -> str:
    return re.sub(r"\r\n|\r|\n", "\r\n", s)


# ---------------------------------------------------------------- network helpers

def discover(host, timeout=1.0):
    """Returns the discovery reply or None."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(b"Hello", (host, DISCOVERY_PORT))
        return s.recvfrom(1024)[0]
    except OSError:
        return None
    finally:
        s.close()


def read_sin(sock, timeout):
    """Reads the SIN handshake. Returns ('sin', info), ('closed', None) or ('silent', None)."""
    deadline = time.monotonic() + timeout
    buf = b""
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return "silent", None
            sock.settimeout(left)
            chunk = sock.recv(1024)
            if not chunk:
                return "closed", None
            buf += chunk
            if len(buf) >= 6 and buf[3:6].isdigit() and len(buf) >= 6 + int(buf[3:6]):
                if buf[:3] != b"SIN":
                    return "closed", None
                return "sin", buf[6:6 + int(buf[3:6])].decode("utf-8", "replace")
    except socket.timeout:
        return "silent", None
    except OSError:  # reset / aborted
        return "closed", None


def connect(host, timeout=2.0):
    """Connects and reads the handshake. Returns (sock, info, seconds) or (None, why, seconds)."""
    t0 = time.perf_counter()
    try:
        sock = socket.create_connection((host, PORT), timeout=timeout)
    except OSError as e:
        return None, f"connect failed: {e}", time.perf_counter() - t0
    status, hs = read_sin(sock, timeout - (time.perf_counter() - t0))
    dt = time.perf_counter() - t0
    if status != "sin":
        sock.close()
        return None, f"no handshake ({status})", dt
    sock.settimeout(5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return sock, hs, dt


def responsive(host, budget=1.0):
    """Discovery and a fresh handshake each within budget seconds (discovery tried twice)."""
    t0 = time.perf_counter()
    reply = discover(host, budget) or discover(host, budget)
    t_disc = time.perf_counter() - t0
    sock, hs, t_hs = connect(host, budget)
    if sock:
        sock.close()
    ok = bool(reply and reply[:2] == b"BC") and sock is not None
    return ok, f"discovery {'ok' if reply else 'none'} {t_disc * 1000:.0f} ms, handshake {'ok' if sock else hs} {t_hs * 1000:.0f} ms"


def close_all(socks):
    for s in socks:
        try:
            s.close()
        except OSError:
            pass


class LogTail:
    """Reads what the server appended to its log after a given position."""

    def __init__(self, path):
        self.path = path

    def size(self):
        try:
            with open(self.path, "rb") as f:
                return f.seek(0, 2)
        except OSError:
            return 0

    def since(self, pos):
        try:
            with open(self.path, "rb") as f:
                f.seek(pos)
                return f.read().decode("utf-8", "replace")
        except OSError:
            return ""


def count_scroll_lines(text, transport="UDP"):
    return len(re.findall(rf"\[{transport} [^\]]*\] mos s 0\s*$", text, re.M))


def settled_count(log, pos, transport="UDP", want=1, timeout=2.0):
    """'mos s 0' lines logged since pos: waits up to timeout for want of them, then 0.5 s more
    so extra (unexpected) lines show up too."""
    deadline = time.monotonic() + timeout
    while count_scroll_lines(log.since(pos), transport) < want and time.monotonic() < deadline:
        time.sleep(0.05)
    time.sleep(0.5)
    return count_scroll_lines(log.since(pos), transport)


def wait_until(pred, timeout):
    deadline = time.monotonic() + timeout
    while not pred():
        if time.monotonic() > deadline:
            return False
        time.sleep(0.05)
    return True


def closed_within(sock, timeout):
    """True if the server closes sock within timeout seconds (what it sends first is dropped)."""
    deadline = time.monotonic() + timeout
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return False
            sock.settimeout(left)
            if not sock.recv(4096):
                return True
    except socket.timeout:
        return False
    except OSError:  # reset
        return True


def capture_report(path):
    """The stress-capture.ps1 report as a dict, or None while it is missing or mid-write."""
    try:
        with open(path, encoding="utf-8-sig") as f:  # Set-Content -Encoding UTF8 writes a BOM
            report = json.load(f)
        return report if isinstance(report, dict) else None
    except (OSError, ValueError):
        return None


def capture_events(path):
    report = capture_report(path)
    if report is None:
        return None
    events = report.get("events") or []
    return [events] if isinstance(events, str) else events  # ConvertTo-Json: one element -> bare string


# ---------------------------------------------------------------- clipboard (ctypes, Windows only)

class Clipboard:
    CF_UNICODETEXT = 13

    def __init__(self):
        import ctypes
        from ctypes import wintypes as w
        self.ct = ctypes
        u = self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        k = self.kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        u.OpenClipboard.argtypes = [w.HWND]
        u.OpenClipboard.restype = w.BOOL
        u.CloseClipboard.restype = w.BOOL
        u.EmptyClipboard.restype = w.BOOL
        u.IsClipboardFormatAvailable.argtypes = [w.UINT]
        u.IsClipboardFormatAvailable.restype = w.BOOL
        u.GetClipboardData.argtypes = [w.UINT]
        u.GetClipboardData.restype = w.HANDLE
        u.SetClipboardData.argtypes = [w.UINT, w.HANDLE]
        u.SetClipboardData.restype = w.HANDLE
        u.GetForegroundWindow.restype = w.HWND
        u.GetWindowTextLengthW.argtypes = [w.HWND]
        u.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, ctypes.c_int]
        k.GlobalAlloc.argtypes = [w.UINT, ctypes.c_size_t]
        k.GlobalAlloc.restype = w.HGLOBAL
        k.GlobalLock.argtypes = [w.HGLOBAL]
        k.GlobalLock.restype = ctypes.c_void_p
        k.GlobalUnlock.argtypes = [w.HGLOBAL]
        k.GlobalSize.argtypes = [w.HGLOBAL]
        k.GlobalSize.restype = ctypes.c_size_t
        k.GlobalFree.argtypes = [w.HGLOBAL]

    def _open(self):
        for _ in range(100):  # another app (or the server) may hold it for a moment
            if self.user32.OpenClipboard(None):
                return
            time.sleep(0.02)
        raise RuntimeError("clipboard stayed locked by another app")

    def read(self):
        self._open()
        try:
            if not self.user32.IsClipboardFormatAvailable(self.CF_UNICODETEXT):
                return None
            h = self.user32.GetClipboardData(self.CF_UNICODETEXT)
            if not h:
                return None
            p = self.kernel32.GlobalLock(h)
            if not p:
                return None
            try:
                raw = self.ct.string_at(p, self.kernel32.GlobalSize(h))
            finally:
                self.kernel32.GlobalUnlock(h)
            text = raw[:len(raw) // 2 * 2].decode("utf-16-le", "replace")
            return text.split("\0", 1)[0]
        finally:
            self.user32.CloseClipboard()

    def write(self, text):
        data = text.encode("utf-16-le") + b"\0\0"
        h = self.kernel32.GlobalAlloc(0x0002, len(data))  # GMEM_MOVEABLE
        p = self.kernel32.GlobalLock(h)
        self.ct.memmove(p, data, len(data))
        self.kernel32.GlobalUnlock(h)
        self._open()
        try:
            self.user32.EmptyClipboard()
            if not self.user32.SetClipboardData(self.CF_UNICODETEXT, h):
                self.kernel32.GlobalFree(h)
                raise RuntimeError("SetClipboardData failed")
        finally:
            self.user32.CloseClipboard()

    def foreground_title(self):
        hwnd = self.user32.GetForegroundWindow()
        n = self.user32.GetWindowTextLengthW(hwnd)
        buf = self.ct.create_unicode_buffer(n + 1)
        self.user32.GetWindowTextW(hwnd, buf, n + 1)
        return buf.value


class FocusLost(Exception):
    pass


def wait_for_capture(clip):
    """Waits up to 30 s for the capture window to be in front; FocusLost if it never is."""
    deadline = time.monotonic() + 30
    waited = False
    while CAPTURE_TITLE not in clip.foreground_title().lower():
        if not waited:
            print("      waiting for the 'AirKeyboard stress capture' window to be in front (30 s)...", flush=True)
            waited = True
        if time.monotonic() > deadline:
            raise FocusLost()
        time.sleep(0.2)


class Mouse:
    """The left button's state, and the cursor, for the held-button checks (ctypes, Windows only)."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes as w
        self.ct, self.w = ctypes, w
        u = self.user32 = ctypes.WinDLL("user32", use_last_error=True)
        u.FindWindowW.argtypes = [w.LPCWSTR, w.LPCWSTR]
        u.FindWindowW.restype = w.HWND
        u.GetWindowRect.argtypes = [w.HWND, ctypes.POINTER(w.RECT)]
        u.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
        u.WindowFromPoint.argtypes = [w.POINT]
        u.WindowFromPoint.restype = w.HWND
        u.GetAncestor.argtypes = [w.HWND, w.UINT]
        u.GetAncestor.restype = w.HWND
        u.GetAsyncKeyState.argtypes = [ctypes.c_int]
        u.GetAsyncKeyState.restype = ctypes.c_short
        u.GetSystemMetrics.argtypes = [ctypes.c_int]
        u.mouse_event.argtypes = [w.DWORD, w.DWORD, w.DWORD, w.DWORD, ctypes.c_size_t]

    def swapped(self):
        return self.user32.GetSystemMetrics(23) != 0  # SM_SWAPBUTTON

    def aim(self):
        """Puts the cursor in the middle of the capture window; False if that spot isn't on it."""
        hwnd = self.user32.FindWindowW(None, CAPTURE_WINDOW)
        if not hwnd:
            return False
        r = self.w.RECT()
        self.user32.GetWindowRect(hwnd, self.ct.byref(r))
        x, y = (r.left + r.right) // 2, (r.top + r.bottom) // 2
        self.user32.SetCursorPos(x, y)
        hit = self.user32.WindowFromPoint(self.w.POINT(x, y))
        return bool(hit) and self.user32.GetAncestor(hit, 2) == hwnd  # GA_ROOT

    def left_down(self):
        return bool(self.user32.GetAsyncKeyState(0x01) & 0x8000)  # VK_LBUTTON

    def local_up(self):
        self.user32.mouse_event(0x0004, 0, 0, 0, 0)  # MOUSEEVENTF_LEFTUP


def mouse_ready(host, clip, what):
    """A Mouse aimed at the capture window, or None (after an INFO or FAIL line) when a button
    can't be pressed safely here."""
    if clip is None:
        info(what, "skipped (needs --paste)")
        return None
    if host not in LOOPBACK:
        info(what, "skipped (needs the server on this PC, host 127.0.0.1)")
        return None
    mouse = Mouse()
    if mouse.swapped():
        info(what, "skipped (mouse buttons are swapped)")
        return None
    try:
        wait_for_capture(clip)
    except FocusLost:
        check(f"{what}: capture window in front", False, "gave up after 30 s")
        return None
    if not mouse.aim():
        check(f"{what}: cursor on the capture window", False, "another window covers its middle")
        return None
    if mouse.left_down():
        check(f"{what}: left button up at the start", False, "it is already down")
        return None
    return mouse


def unstick(mouse, release):
    """Leaves the left button up: release() first, then a local button-up (and a FAIL)."""
    if not mouse.left_down():
        return
    release()
    if not wait_until(lambda: not mouse.left_down(), 0.5):
        mouse.local_up()
        check("button left stuck", False, "released locally")


# ---------------------------------------------------------------- password (rmauth1)

SECRETS = set()  # every key, proof and nonce this run produced; none may reach the server log


def remember(*values):
    for v in values:
        SECRETS.update((base64.b64encode(v).decode(), v.hex()))


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed by the server")
        buf += chunk
    return buf


def read_msg(sock, marker):
    """Reads one server message framed like SIN (3-char marker + 3-digit length + body)."""
    head = recv_exact(sock, 6)
    if head[:3] != marker or not head[3:6].isdigit():
        raise ValueError(f"expected {marker.decode()}, got {head!a}")
    return recv_exact(sock, int(head[3:6]))


def parse_chl(body):
    """'1 <salt b64> <iterations> <nonceS b64>' -> (salt, iterations, nonceS); ValueError if bad."""
    fields = body.decode("ascii").split(" ")
    if len(fields) != 4 or fields[0] != "1" or not fields[2].isdigit():
        raise ValueError(f"bad chl {body!a}")
    salt, nonce = base64.b64decode(fields[1], validate=True), base64.b64decode(fields[3], validate=True)
    if len(salt) != 16 or len(nonce) != 16 or not 100_000 <= int(fields[2]) <= 1_000_000:
        raise ValueError(f"bad chl {body!a}")
    return salt, int(fields[2]), nonce


@functools.lru_cache(maxsize=8)
def derive(pw, salt, iterations):
    """K = PBKDF2-HMAC-SHA256(UTF-8(NFC(pw)), salt, iterations, 32)"""
    return hashlib.pbkdf2_hmac("sha256", unicodedata.normalize("NFC", pw).encode("utf-8"), salt, iterations, 32)


def proofs(key, nonce_s, nonce_c):
    """(proofC, proofS, SKmac) for n = nonceS || nonceC"""
    n = nonce_s + nonce_c
    h = lambda label: hmac.new(key, label + n, hashlib.sha256).digest()
    return h(b"rmauth1 client"), h(b"rmauth1 server"), h(b"rmauth1 mac")


def tag(sk, transport, data):
    """First 16 bytes of HMAC(SKmac, T || frame); T is b'T' (TCP) or b'U' (UDP)."""
    return hmac.new(sk, transport + data, hashlib.sha256).digest()[:16]


def open_auth(host, timeout=3.0):
    """Connects to a password server and reads SIN and chl. Returns (sock, sin body, chl body)."""
    sock = socket.create_connection((host, PORT), timeout=timeout)
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        return sock, read_msg(sock, b"SIN"), read_msg(sock, b"chl")
    except (OSError, ValueError):
        sock.close()
        raise


class AuthClient:
    """A logged-in session: frames after 'cin ok' carry a tag; ath was chunk id 0, so ids go on at 1."""

    def __init__(self, sock, sk, nonce_s):
        self.sock, self.sk, self.nonce_s, self.next_id = sock, sk, nonce_s, 1

    def tagged(self, transport, kind, data, cid=None):
        if cid is None:
            cid, self.next_id = self.next_id, self.next_id + 1
        f = encrypt_chunk(command(kind, data.encode()), cid)
        return f + tag(self.sk, transport, f)


def login(host, pw, wrong=False, pause=0.0):
    """Sends ath (with a wrong proof if asked), pause seconds after the challenge arrived.
    Returns (cin body, AuthClient, proofS valid). The caller closes client.sock."""
    sock, _, chl = open_auth(host)
    try:
        salt, iterations, nonce_s = parse_chl(chl)
        key = derive(pw, salt, iterations)
        nonce_c = os.urandom(16)
        proof_c, proof_s, sk = proofs(key, nonce_s, nonce_c)
        remember(key, nonce_s, nonce_c, proof_c, proof_s, sk)
        if wrong:
            proof_c = bytes(b ^ 0xFF for b in proof_c)
        if pause:
            time.sleep(pause)
        ath = f"1 {base64.b64encode(nonce_c).decode()} {base64.b64encode(proof_c).decode()}"
        sock.sendall(encrypt_chunk(command(b"ath", ath.encode()), 0))
        reply = read_msg(sock, b"cin").decode("ascii", "replace")
    except (OSError, ValueError):
        sock.close()
        raise
    parts = reply.split(" ")
    try:
        valid = parts[0] == "ok" and len(parts) == 2 and hmac.compare_digest(base64.b64decode(parts[1], validate=True), proof_s)
    except ValueError:
        valid = False
    return reply, AuthClient(sock, sk, nonce_s), valid


class PreauthFlood(threading.Thread):
    """Keeps per_source silent (never logging in) connections open from each source address
    to the server on this PC, opening a new one as soon as the server closes one, until done
    is set. fill() opens the first ones; start() then keeps them topped up."""

    def __init__(self, sources, per_source=2):
        super().__init__(daemon=True)
        self.sources, self.per_source = sources, per_source
        self.socks = {}  # socket -> source address
        self.reconnects = 0
        self.errors = 0
        self.done = threading.Event()

    def open(self, source):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind((source, 0))
            s.settimeout(2.0)
            s.connect(("127.0.0.1", PORT))
        except OSError:
            s.close()
            self.errors += 1
            return
        self.socks[s] = source

    def fill(self):
        for source in self.sources:
            for _ in range(self.per_source - list(self.socks.values()).count(source)):
                self.open(source)

    def run(self):
        try:
            while not self.done.is_set():
                if not self.socks:
                    time.sleep(0.02)
                else:
                    ready, _, _ = select.select(list(self.socks), [], [], 0.02)
                    for s in ready:
                        try:
                            data = s.recv(4096)  # the SIN and chl, or the server closing it
                        except OSError:  # reset
                            data = b""
                        if not data:
                            del self.socks[s]
                            s.close()
                            self.reconnects += 1
                self.fill()
                time.sleep(0.01)  # fast enough to rotate a login out of an oldest-first pool, gentler on the log
        finally:
            close_all(list(self.socks))


def close_times(socks, timeout):
    """Seconds from now until the server closed each socket (None if still open after timeout)."""
    t0 = time.monotonic()
    times = [None] * len(socks)
    pending = dict(enumerate(socks))
    while pending:
        left = t0 + timeout - time.monotonic()
        if left <= 0:
            break
        ready, _, _ = select.select(list(pending.values()), [], [], left)
        for i, s in list(pending.items()):
            if s in ready:
                try:
                    data = s.recv(4096)
                except OSError:  # reset
                    data = b""
                if not data:
                    times[i] = time.monotonic() - t0
                    del pending[i]
    return times


# ---------------------------------------------------------------- sections

def section_a(host, log):
    print("\n== A. baseline")
    reply = discover(host, 2.0)
    ok = bool(reply and reply[:2] == b"BC" and reply[2:5].isdigit())
    check("discovery", ok, f"{reply!a}")
    sock, hs, dt = connect(host, 3.0)
    # '@rm1' marks AirKeyboard's handshake; the app refuses any server that doesn't send it
    ok = check("handshake", sock is not None and hs.endswith(" nopwd @rm1 530"), f"{hs!a} in {dt * 1000:.0f} ms") and ok
    if sock is not None:
        # The app splits the handshake on spaces, so a name with spaces must not shift the fields
        fields = hs.split(" ")
        check("handshake is 4 space-separated fields", len(fields) == 4 and fields[1] in ("pwd", "nopwd")
              and fields[3].isdigit(), f"{hs!a}")
    # Without a password nothing follows the SIN (a password server sends 'chl' in the same write)
    try:
        raw = socket.create_connection((host, PORT), timeout=3.0)
    except OSError as e:
        check("no chl after the nopwd handshake", False, f"connect failed: {e}")
    else:
        try:
            read_msg(raw, b"SIN")
            raw.settimeout(0.5)
            try:
                more = raw.recv(1024)
                detail = f"got {more[:40]!a}" if more else "the server closed the connection"
            except socket.timeout:
                more, detail = None, "nothing more within 0.5 s"
            check("no chl after the nopwd handshake", more is None, detail)
        except (OSError, ValueError) as e:
            check("no chl after the nopwd handshake", False, str(e))
        finally:
            raw.close()
    sock2, hs2, _ = connect(host, 3.0)
    ok = check("second concurrent client", sock2 is not None, hs2 if sock2 is None else "handshake while first is open") and ok
    close_all([s for s in (sock, sock2) if s])

    # Hello replies are rate-limited per address (at most one per 100 ms)
    time.sleep(0.2)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0.5)
    replies = 0
    try:
        for _ in range(5):
            s.sendto(b"Hello", (host, DISCOVERY_PORT))
        while True:
            s.recvfrom(1024)
            replies += 1
    except OSError:  # includes the timeout once no more replies come
        pass
    finally:
        s.close()
    check("discovery rate limit", replies == 1, f"{replies} replies to 5 Hellos sent at once")
    time.sleep(0.2)

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    pos = log.size() if log else 0
    udp.sendto(frame(b"mos", "s 0"), (host, PORT))
    udp.close()
    if log:
        deadline = time.monotonic() + 2
        while count_scroll_lines(log.since(pos)) < 1 and time.monotonic() < deadline:
            time.sleep(0.1)
        check("UDP command logged", count_scroll_lines(log.since(pos)) >= 1, "'mos s 0' in the server log")
        # Wi-Fi now and then delivers a datagram twice: the copies run once, the next frame still runs
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        pos = log.size()
        dup = frame(b"mos", "s 0")
        for _ in range(3):
            udp.sendto(dup, (host, PORT))
        n_dup = settled_count(log, pos)
        pos = log.size()
        udp.sendto(frame(b"mos", "s 0"), (host, PORT))
        n_next = settled_count(log, pos)
        udp.close()
        check("UDP duplicate datagram runs once", n_dup == 1 and n_next == 1,
              f"{n_dup} lines for 1 frame sent 3 times, {n_next} for the next frame")
    else:
        info("UDP command", "mos s 0 sent (pass --log to confirm it arrived)")
    return ok


def section_b(host, log, clip, capture):
    print("\n== B. clipboard paste over TCP (clp)")
    try:
        original = clip.read()
    except RuntimeError as e:
        check("clipboard access", False, f"{e}; paste cases skipped")
        return
    log_start = log.size() if log else 0
    seq = [0]
    sent = []  # normalized text of every pasted() case sent, in order
    typed_text = ""  # the text typed over TCP at the end (not a paste), once sent

    def require_focus():
        wait_for_capture(clip)

    def sentinel():
        seq[0] += 1
        s = f"stress-sentinel-{seq[0]}-{random.randrange(10**9)}"
        clip.write(s)
        return s

    def wait_clip(want, timeout):
        deadline = time.monotonic() + timeout
        got = None
        while time.monotonic() < deadline:
            got = clip.read()
            if got == want:
                return True, got
            time.sleep(0.1 if len(want) < 100_000 else 0.3)
        return False, got

    def describe(want, got):
        if got is None:
            return "clipboard has no text"
        at = next((i for i, (a, b) in enumerate(zip(want, got)) if a != b), min(len(want), len(got)))
        return f"expected {len(want)} chars, got {len(got)}; first difference at {at}: {got[max(0, at - 20):at + 20]!a}"

    def capture_text():
        report = capture_report(capture)  # None while missing, or caught mid-write
        return None if report is None else report.get("text") or ""

    def landed(want, pos):
        """After the clipboard matched: waits until the server pressed Ctrl+V and the window took
        the paste, so the next clipboard write can't be pasted instead. Returns an error or None."""
        if log:
            n = len(want.encode("utf-16-le")) // 2  # the server counts UTF-16 units
            line = re.compile(rf"\] clp paste {n} chars")
            deadline = time.monotonic() + 5
            while not line.search(log.since(pos)):
                if time.monotonic() > deadline:
                    return f"clipboard set, but no 'clp paste {n} chars' log line (Ctrl+V not sent?)"
                time.sleep(0.05)
        if capture:
            deadline = time.monotonic() + (8 if len(want) < 100_000 else 40)  # report written every 2 s
            got = None
            while time.monotonic() < deadline:
                got = capture_text()
                if got is not None and got.endswith(want):
                    return None
                time.sleep(0.5)
            return "clipboard set, but the capture window " + (
                "report is unreadable" if got is None else f"text does not end with it (ends {got[-40:]!a})")
        time.sleep(0.5 + len(want) / 250_000)  # let the window handle the queued Ctrl+V
        return None

    def pasted(name, text, send, timeout=3.0):
        """send() delivers a transfer of text; the clipboard must end up as text with CRLF,
        and (with --log / --capture) the Ctrl+V must be logged / reach the capture window."""
        sentinel()
        require_focus()
        want = norm_crlf(text)
        pos = log.size() if log else 0
        t0 = time.perf_counter()
        send()
        sent.append(want)
        ok, got = wait_clip(want, timeout)
        if not ok:
            return check(name, False, describe(want, got))
        dt = time.perf_counter() - t0
        err = landed(want, pos)
        return check(name, err is None, err or f"{len(want)} chars in {dt * 1000:.0f} ms")

    def not_pasted(name, action, wait=1.2):
        s = sentinel()
        require_focus()
        action()
        time.sleep(wait)
        got = clip.read()
        return check(name, got == s, "clipboard unchanged" if got == s else f"clipboard changed to {len(got or '')} chars")

    conn, hs, _ = connect(host, 3.0)
    if not conn:
        check("clp connection", False, hs)
        return
    extra = []
    try:
        # Typed text is never logged, not even its length (the end-of-section check covers MARKER)
        require_focus()
        pos = log.size() if log else 0
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.sendto(frame(b"key", f"ttyped {MARKER} "), (host, PORT))
        udp.close()
        time.sleep(0.5)
        if log:
            since = log.since(pos)
            check("typed text not logged", "] key text (" not in since and MARKER not in since,
                  "no 'key text' line, no marker")

        t = f"stress small ascii {MARKER} 123"
        pasted("small ASCII", t, lambda: clp_send(conn, t.encode()))

        t = (f"Unicode {MARKER}: emoji \U0001F600\U0001F44D\U0001F3FD \U0001F468\u200d\U0001F469\u200d\U0001F467 "
             "CJK \u6f22\u5b57\u304b\u306a\u30ab\u30ca \ud55c\uad6d\uc5b4 combining e\u0301 a\u0308 n\u0303 "
             "RTL \u05e2\u05d1\u05e8\u05d9\u05ea \u0627\u0644\u0639\u0631\u0628\u064a\u0629 end")
        pasted("Unicode", t, lambda: clp_send(conn, t.encode()))

        t = f"{MARKER} a\nb\r\nc\rd\n\ne\r\r\nf\r"
        pasted("mixed line endings", t, lambda: clp_send(conn, t.encode()))

        t = MARKER + "\U0001F600" * 600  # 4-byte code points; 997-byte slices split them mid-way
        pasted("slices split mid-code-point (997)", t, lambda: clp_send(conn, t.encode(), slice_=997))

        t = f"{MARKER} \u00e9\u6f22\U0001F600 one byte per slice"
        pasted("1-byte slices", t, lambda: clp_send(conn, t.encode(), slice_=1))

        t = "".join(f"{MARKER} coalesced line {i:03d}\n" for i in range(120))  # ~4 KB, 5 slices
        pasted("coalesced frames (one sendall)", t, lambda: clp_send(conn, t.encode(), mode="coalesced"))

        t = f"{MARKER} dribbled one byte per TCP write"
        pasted("fragmented frames (1 byte per write)", t, lambda: clp_send(conn, t.encode(), mode="dribble"), timeout=5)

        # a "b" in the middle of a transfer discards what came before it
        t = f"fresh {MARKER}"
        pasted("b mid-transfer discards the partial buffer", t,
               lambda: (conn.sendall(frame(b"clp", b"b") + frame(b"clp", b"d" + f"stale {MARKER} ".encode())),
                        clp_send(conn, t.encode())))

        # invalid UTF-8 at "e" is dropped; empty text at "e" does nothing
        pos = log.size() if log else 0
        not_pasted("invalid UTF-8 dropped", lambda: clp_send(conn, b"\xff\xfe" + MARKER.encode()))
        not_pasted("code point cut off at the end dropped", lambda: clp_send(conn, MARKER.encode() + "漢".encode()[:2]))
        if log:
            n = log.since(pos).count("clp dropped: invalid UTF-8")
            check("invalid UTF-8 logged", n == 2, f"{n} 'clp dropped: invalid UTF-8' lines for 2 transfers")
        not_pasted("empty text (b then e) does nothing", lambda: clp_send(conn, b""))

        # d / e without b, on a fresh connection, then a normal transfer on it still works
        c2, why, _ = connect(host, 2.0)
        if not c2:
            check("d/e without b", False, why)
        else:
            extra.append(c2)
            not_pasted("d/e without b ignored", lambda: (c2.sendall(frame(b"clp", b"d" + f"orphan {MARKER}".encode())),
                                                         c2.sendall(frame(b"clp", b"e"))))
            t = f"{MARKER} after orphan d/e"
            pasted("transfer after ignored d/e", t, lambda: clp_send(c2, t.encode()))

        # disconnect mid-transfer: no paste
        c3, why, _ = connect(host, 2.0)
        if not c3:
            check("disconnect mid-transfer", False, why)
        else:
            def cut():
                c3.sendall(frame(b"clp", b"b") + frame(b"clp", b"d" + f"cut {MARKER}".encode()))
                time.sleep(0.2)
                c3.close()
            not_pasted("disconnect mid-transfer: no paste", cut)

        # two connections interleave their transfers; each pastes its own text
        ca, why_a, _ = connect(host, 2.0)
        cb, why_b, _ = connect(host, 2.0)
        extra += [s for s in (ca, cb) if s]
        if not (ca and cb):
            check("interleaved connections", False, f"{why_a} / {why_b}")
        else:
            ta = ("A " + MARKER + " ") * 150   # ~3 KB each, so both are mid-transfer at once
            tb = ("B " + MARKER + " ") * 150
            fa, fb = clp_frames(ta.encode()), clp_frames(tb.encode())
            for i in range(max(len(fa), len(fb)) - 1):  # everything but the final "e"
                if i < len(fa) - 1:
                    ca.sendall(fa[i])
                if i < len(fb) - 1:
                    cb.sendall(fb[i])
            pasted("interleaved: connection A", ta, lambda: ca.sendall(fa[-1]))
            pasted("interleaved: connection B", tb, lambda: cb.sendall(fb[-1]))

        # clp over UDP is ignored
        pos = log.size() if log else 0
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        not_pasted("clp over UDP ignored",
                   lambda: [udp.sendto(f, (host, PORT)) for f in clp_frames(f"udp {MARKER}".encode())])
        udp.close()
        if log:
            n = log.since(pos).count("clp over UDP ignored")
            check("clp over UDP logged once per frame", n == 3, f"{n} log lines for 3 frames")

        # exactly 1 MiB is accepted (CRLF lines, so normalization keeps the size)
        line = (MARKER + "-" + "x" * (62 - len(MARKER) - 1) + "\r\n").encode()
        big = line * (CLP_LIMIT // len(line))
        assert len(big) == CLP_LIMIT
        pasted("exactly 1,048,576 bytes accepted", big.decode(), lambda: clp_send(conn, big), timeout=20)

        # one byte more is rejected; further d/e in the same transfer are ignored until the next b
        def overflow():
            frames = clp_frames(big + b"x")  # the last "d" slice crosses the limit
            for f in frames[:-1]:
                conn.sendall(f)
            conn.sendall(frame(b"clp", b"d" + f"late {MARKER}".encode()))
            conn.sendall(frames[-1])  # "e"
        not_pasted("1,048,577 bytes rejected, later d/e ignored", overflow, wait=3.0)
        t = f"{MARKER} after rejection"
        pasted("transfer after rejection", t, lambda: clp_send(conn, t.encode()))

        # Live typing: one "key t<code point>" per character over the TCP session, and a typo
        # taken back with BACKSPACE. Not a paste, so it stays out of `sent`; the end-of-section
        # MARKER check shows key text is never logged.
        require_focus()
        typed = f"live typing ÄÖÜ \U0001F600 漢字 {MARKER}"
        for i, cp in enumerate(typed):
            conn.sendall(frame(b"key", "t" + cp))
            if i == 4:
                conn.sendall(frame(b"key", "tx"))
                conn.sendall(frame(b"key", "[BACKSPACE]"))
        typed_text = typed
        if capture:
            ok = wait_until(lambda: (capture_text() or "").endswith(typed), 8.0)
            check("typed over TCP, per code point", ok, f"{len(typed)} code points, 1 corrected" if ok
                  else "the capture window text does not end with the typed text")
        else:
            time.sleep(1.0)
            info("typed over TCP", f"{len(typed)} code points sent (pass --capture to check them)")
    except FocusLost:
        check("capture window in front", False, "gave up after 30 s; remaining paste cases skipped")
    except OSError as e:
        check("clp connection", False, f"socket error: {e}")
    except RuntimeError as e:
        check("clipboard access", False, f"{e}; remaining paste cases skipped")
    finally:
        close_all([conn] + extra)
        time.sleep(1.5)  # a Ctrl+V still queued in the window must not paste the restored text
        if original is not None:
            try:
                clip.write(original)
                print("      (your clipboard text was restored)")
            except RuntimeError as e:
                check("clipboard restored", False, str(e))
    if capture:
        got = capture_text()
        whole = "".join(sent) + typed_text
        check("capture window holds exactly the pasted texts, in order", got is not None and got.endswith(whole),
              f"{len(sent)} pastes, {len(whole)} chars" if got is not None and got.endswith(whole)
              else "report unreadable" if got is None else f"window text ({len(got)} chars) does not end with them")
    if log:
        text = log.since(log_start)
        n = len(re.findall(r"\] clp paste \d+ chars", text))
        check("one 'clp paste' log line per paste", n == len(sent), f"{n} lines for {len(sent)} pastes")
        n = text.count("Error executing clp")
        check("no clp errors logged", n == 0, f"{n} 'Error executing clp' lines")
        check("pasted text never logged", MARKER not in text, "marker absent from the log" if MARKER not in text else "MARKER FOUND in the log")


def section_h(host, log, clip, capture):
    print("\n== H. held buttons")
    mouse = mouse_ready(host, clip, "held buttons")
    if mouse is None:
        return
    addr = (host, PORT)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    send = lambda cmd: udp.sendto(frame(b"mos", cmd), addr)
    is_down, is_up = mouse.left_down, lambda: not mouse.left_down()
    try:
        send("p 1")
        down = wait_until(is_down, 1.0)
        send("r 1")
        up = wait_until(is_up, 1.0)
        check("H1 press and release over UDP", down and up, f"down {'yes' if down else 'NO'}, up {'yes' if up else 'NO'}")

        if capture:
            time.sleep(2.5)  # the report is written at most every 2 s: let it show H1 first
            count = lambda evs, name: sum(1 for e in evs if e.startswith(name + " "))
            before = capture_events(capture) or []
            for cmd in ("p 1", "p 1", "p 1", "r 1", "r 1", "r 1"):
                send(cmd)
                time.sleep(0.01)
            wait_until(lambda: count(capture_events(capture) or [], "up Left") > count(before, "up Left"), 6.0)
            time.sleep(2.5)
            after = capture_events(capture) or []
            downs = count(after, "down Left") - count(before, "down Left")
            ups = count(after, "up Left") - count(before, "up Left")
            check("H2 duplicate p/r are idempotent", downs == 1 and ups == 1, f"3 p + 3 r gave {downs} down, {ups} up")
        else:
            info("H2 duplicate p/r", "skipped (needs --capture)")

        pos = log.size() if log else 0
        send("p 1")
        t0 = time.monotonic()
        wait_until(is_down, 1.0)
        time.sleep(max(0.0, t0 + 3.0 - time.monotonic()))
        early = not mouse.left_down()
        released = wait_until(is_up, t0 + 7.5 - time.monotonic())
        dt = time.monotonic() - t0
        ok = not early and released and dt >= 4.5
        detail = "released before 3 s" if early else f"released after {dt:.1f} s" if released else "still down at 7.5 s"
        if log:
            logged = "[held] Left released: no input for 5 s" in log.since(pos)
            ok = ok and logged
            detail += "" if logged else ", no '[held] Left released: no input for 5 s' line"
        check("H3 released 5 s after the last input", ok, detail)

        send("p 1")
        wait_until(is_down, 1.0)
        for _ in range(8):
            time.sleep(1.0)
            send("m 0 0")  # the app's keepalive while a drag is held still
        held = mouse.left_down()
        send("r 1")
        up = wait_until(is_up, 1.0)
        check("H4 keepalive holds the button for 8 s", held and up, f"held {'yes' if held else 'NO'}, up {'yes' if up else 'NO'}")

        for name, over_tcp in (("H5 session end releases a UDP press", False), ("H6 session end releases a TCP press", True)):
            s, why, _ = connect(host, 2.0)
            if not s:
                check(name, False, why)
                continue
            if over_tcp:
                s.sendall(frame(b"mos", "p 1"))
            else:
                send("p 1")
            down = wait_until(is_down, 1.0)
            pos = log.size() if log else 0
            s.close()
            up = wait_until(is_up, 1.0)
            ok, detail = down and up, f"down {'yes' if down else 'NO'}, up after close {'yes' if up else 'NO'}"
            if log:
                time.sleep(0.2)
                logged = "[held] Left released: session ended" in log.since(pos)
                ok, detail = ok and logged, detail + ("" if logged else ", no 'session ended' line")
            check(name, ok, detail)
    finally:
        unstick(mouse, lambda: send("r 1"))
        udp.close()


def section_c(host, log):
    print("\n== C. UDP flood")
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(1)
    frames = [frame(b"mos", "m 0 0") for _ in range(20_000)]
    errors = 0
    t0 = time.perf_counter()
    for f in frames:
        try:
            udp.sendto(f, (host, PORT))
        except OSError:
            errors += 1
    dt = time.perf_counter() - t0
    info("flood", f"20,000 'mos m 0 0' in {dt:.2f} s ({20_000 / dt:,.0f}/s), {errors} send errors")
    time.sleep(1.0)

    pos = log.size() if log else 0
    rate = 250
    t0 = time.perf_counter()
    for i in range(2_000):
        try:
            udp.sendto(frame(b"mos", "s 0"), (host, PORT))
        except OSError:
            errors += 1
        lag = t0 + (i + 1) / rate - time.perf_counter()
        if lag > 0:
            time.sleep(lag)
    info("scroll", f"2,000 'mos s 0' at {rate}/s in {time.perf_counter() - t0:.1f} s")
    udp.close()

    ok, detail = responsive(host, 1.0)
    check("responsive after flood", ok, detail)

    if log:
        last, stable_since, deadline = -1, time.monotonic(), time.monotonic() + 10
        while time.monotonic() < deadline and time.monotonic() - stable_since < 1.0:
            n = count_scroll_lines(log.since(pos))
            if n != last:
                last, stable_since = n, time.monotonic()
            time.sleep(0.2)
        loss = (2_000 - last) / 2_000 * 100
        loopback = host in ("127.0.0.1", "localhost", "::1")
        msg = f"{last}/2000 logged, {loss:.1f}% lost"
        if loopback:
            check("UDP loss (loopback, <= 5%)", loss <= 5, msg)
        else:
            info("UDP loss", msg)


def section_i(host, log):
    print("\n== I. commands over the TCP session")
    sock, hs, _ = connect(host, 3.0)
    if not sock:
        check("TCP session", False, hs)
        return
    pos = log.size() if log else 0
    try:
        t0 = time.perf_counter()
        for _ in range(2_000):  # one frame per write, as the app sends live typing
            sock.sendall(frame(b"mos", "s 0"))
        info("TCP commands", f"2,000 'mos s 0' in {time.perf_counter() - t0:.2f} s")
    except OSError as e:
        check("TCP commands sent", False, f"socket error: {e}")
        sock.close()
        return
    ok, detail = responsive(host, 1.0)
    check("responsive during TCP commands", ok, detail)
    if log:
        last, stable_since, deadline = -1, time.monotonic(), time.monotonic() + 10
        while time.monotonic() < deadline and time.monotonic() - stable_since < 1.0:
            n = count_scroll_lines(log.since(pos), "TCP")
            if n != last:
                last, stable_since = n, time.monotonic()
            time.sleep(0.2)
        check("TCP commands: 0 lost", last == 2_000, f"{last}/2000 logged")
    sock.close()


def section_d(host):
    print("\n== D. TCP concurrency")
    socks = []
    for _ in range(30):
        try:
            socks.append(socket.create_connection((host, PORT), timeout=2))
        except OSError:
            pass
    deadline = time.monotonic() + 2
    got = sum(read_sin(s, max(0.01, deadline - time.monotonic()))[0] == "sin" for s in socks)
    check("30 concurrent handshakes within 2 s", got == 30, f"{got}/30")
    close_all(socks)
    time.sleep(0.5)

    socks, refused = [], 0
    for _ in range(40):
        try:
            socks.append(socket.create_connection((host, PORT), timeout=2))
        except OSError:
            refused += 1
    deadline = time.monotonic() + 2
    states = [read_sin(s, max(0.01, deadline - time.monotonic()))[0] for s in socks]
    sin, closed, silent = states.count("sin"), states.count("closed") + refused, states.count("silent")
    check("40 at once: at most 32 handshakes (cap)", sin <= 32, f"{sin} handshakes, {closed} closed/refused, {silent} silent")
    close_all(socks)
    time.sleep(0.5)
    ok, detail = responsive(host, 1.0)
    check("responsive after cap", ok, detail)

    times, fails = [], 0
    for _ in range(300):
        s, why, dt = connect(host, 2.0)
        if s:
            times.append(dt * 1000)
            s.close()
        else:
            fails += 1
    times.sort()
    if times:
        p = lambda q: times[min(len(times) - 1, int(q * len(times)))]
        detail = f"{len(times)}/300 ok, p50 {p(0.5):.1f} ms, p95 {p(0.95):.1f} ms, max {times[-1]:.1f} ms"
    else:
        detail = "no handshake succeeded"
    check("300 sequential connect/handshake/close", fails == 0, detail)

    # One non-loopback address holds at most 4 connections; a 5th replaces that address's oldest.
    # Connecting to this PC's own LAN address makes the server see that address, not loopback.
    lan = lan_address() if host in ("127.0.0.1", "localhost") else None
    if not lan:
        info("per-address limit", "skipped (needs the server on this PC and a LAN address)")
        return
    socks = []
    for _ in range(6):
        s, why, _ = connect(lan, 2.0)
        if s:
            socks.append(s)
    time.sleep(0.5)
    closed = []
    for s in socks:
        try:
            s.settimeout(0.2)
            closed.append(s.recv(1) == b"")
        except socket.timeout:
            closed.append(False)
        except OSError:  # reset
            closed.append(True)
    close_all(socks)
    check("per-address limit: newest 4 of 6 stay open", len(socks) == 6 and closed == [True, True] + [False] * 4,
          f"via {lan}: {len(socks)} handshakes, closed by the server: {closed}")
    time.sleep(0.5)

    # At the global cap a new address takes a slot from the busiest address instead of being
    # refused (an attacker with several IP aliases can't lock the phone out). Loopback fills the cap.
    socks = []
    for _ in range(40):
        s, why, _ = connect(host, 1.0)
        if not s:
            break
        socks.append(s)
    s, hs, _ = connect(lan, 2.0)
    stays = False
    if s:
        try:
            s.settimeout(0.5)
            stays = s.recv(1) != b""
        except socket.timeout:
            stays = True
        except OSError:
            pass
        s.close()
    close_all(socks)
    check("at the cap, a fresh address is admitted", len(socks) >= 32 and stays,
          f"{len(socks)} loopback connections, then {lan}: {'admitted' if stays else hs if not s else 'closed'}")


def lan_address():
    """This PC's LAN IPv4 address, or None. Connecting a UDP socket sends no packet."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        addr = s.getsockname()[0]
        return None if addr.startswith("127.") or addr == "0.0.0.0" else addr
    except OSError:
        return None
    finally:
        s.close()


def section_e(host):
    print("\n== E. slowloris")
    socks = []
    partial = b"\x23" + struct.pack("<fi", 3.0, 7) + b"\x00"  # 10 bytes of a header, then nothing
    for _ in range(20):
        s, why, _ = connect(host, 2.0)
        if s:
            try:
                s.sendall(partial)
                socks.append(s)
            except OSError:
                s.close()
    info("stalled connections", f"{len(socks)}/20 holding a partial header")
    s, why, dt = connect(host, 1.0)
    check("fresh handshake while 20 stall", s is not None, f"{dt * 1000:.0f} ms" if s else why)
    if s:
        s.close()
    close_all(socks)


def fuzz_header(rng):
    return encrypt_chunk(b"", rng.randrange(2**31), rng)


def fuzz_udp(rng):
    """One malformed datagram (may be unsafe; callers check is_safe)."""
    kind = rng.randrange(6)
    if kind == 0:  # random bytes, any length, not starting with the header byte
        f = rng.randbytes(rng.randint(0, 1500))
        return b"\x00" + f[1:] if f[:1] == b"\x23" else f
    if kind == 1:  # valid header byte, the rest of the header and the payload garbage
        return seal(b"\x23" + rng.randbytes(37), garbage(rng, rng.randint(0, 1462)))
    if kind == 2:  # truncated header
        return fuzz_header(rng)[:rng.randint(0, 37)]
    t = fuzz_type(rng)
    body = garbage(rng, rng.randint(0, 200))
    if kind == 3:  # bad length digits
        return encrypt_chunk(t + rng.choice([b"-01", b"+05", b"1a2", b" 1 ", b"1.5", b"0x9", b"999"[:rng.randint(0, 2)]]) + body, 0, rng)
    if kind == 4:  # length larger than the data
        n = rng.randint(len(body) + 1, 999)
        return encrypt_chunk(t + f"{n:03d}".encode() + body, 0, rng)
    # non-digit length
    return encrypt_chunk(t + garbage(rng, 3).translate(bytes.maketrans(b"0123456789", b"ABCDEFGHIJ")) + body, 0, rng)


TCP_FUZZ_KINDS = ["bad header byte", "non-digit length", "length beyond data", "truncated"]


def fuzz_tcp(rng, kind):
    """One malformed TCP frame of the given kind (may be unsafe; callers check is_safe)."""
    if kind == "bad header byte":
        f = encrypt_chunk(command(fuzz_type(rng), garbage(rng, 10)), 0, rng)
        return bytes([rng.choice([b for b in range(256) if b != HEADER_BYTE])]) + f[1:]
    if kind == "non-digit length":
        return encrypt_chunk(fuzz_type(rng) + b"x?z" + garbage(rng, 20), 0, rng)
    if kind == "length beyond data":
        return encrypt_chunk(fuzz_type(rng) + b"500" + garbage(rng, 10), 0, rng)
    return fuzz_header(rng)[:rng.randint(1, 37)]  # truncated


def section_f(host, rng):
    print("\n== F. fuzz")
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(1)
    regenerated = errors = 0
    t0 = time.perf_counter()
    for _ in range(10_000):
        f = fuzz_udp(rng)
        while not is_safe(f):
            regenerated += 1
            f = fuzz_udp(rng)
        try:
            udp.sendto(f, (host, PORT))
        except OSError:
            errors += 1
    udp.close()
    info("UDP fuzz", f"10,000 malformed datagrams in {time.perf_counter() - t0:.1f} s "
                     f"({regenerated} regenerated as unsafe, {errors} send errors)")
    time.sleep(0.5)
    ok, detail = responsive(host, 1.0)
    check("responsive after UDP fuzz", ok, detail)

    server_closed = {k: 0 for k in TCP_FUZZ_KINDS}
    opened = 0
    for batch in range(5):
        batch_socks = []
        for i in range(20):
            kind = TCP_FUZZ_KINDS[(batch * 20 + i) % 4]
            s, why, _ = connect(host, 2.0)
            if not s:
                continue
            opened += 1
            f = fuzz_tcp(rng, kind)
            while not is_safe(f):
                f = fuzz_tcp(rng, kind)
            try:
                s.sendall(f)
            except OSError:
                pass
            if kind in ("truncated", "length beyond data"):
                s.close()  # the frame never completes
            else:
                batch_socks.append((s, kind))
        deadline = time.monotonic() + 1.0
        for s, kind in batch_socks:
            try:
                s.settimeout(max(0.01, deadline - time.monotonic()))
                if s.recv(1024) == b"":
                    server_closed[kind] += 1
            except socket.timeout:
                pass
            except OSError:
                server_closed[kind] += 1
        close_all(s for s, _ in batch_socks)
    info("TCP fuzz", f"{opened}/100 connections sent one malformed frame; closed by the server: "
                     f"bad header byte {server_closed['bad header byte']}/25, non-digit length {server_closed['non-digit length']}/25")
    check("TCP fuzz connections", opened == 100, f"{opened}/100 got a handshake")
    time.sleep(0.3)
    ok, detail = responsive(host, 1.0)
    check("legit client after TCP fuzz", ok, detail)


def section_j(host, log):
    print("\n== J. log hygiene")
    if not log:
        info("log hygiene", "skipped (needs --log)")
        return
    time.sleep(10.5)  # drop messages are rate-limited per 10 s; start a fresh window after section F
    pos = log.size()
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # a "key" frame whose length field is larger than its data: dropped, typed text not echoed
    udp.sendto(encrypt_chunk(b"key900t" + MARKER.encode()), (host, PORT))
    # a combo naming an unknown key: rejected before any input; the name is not logged
    udp.sendto(frame(b"key", f"[CTRL+{MARKER}]"), (host, PORT))
    # a 40 KB junk payload with line breaks: not echoed, so it can neither bloat nor forge lines
    junk = encrypt_chunk(b"zzz?!?" + b"\r\n[2026-01-01 00:00:00.000] forged line " * 1000)
    assert is_safe(junk)
    udp.sendto(junk, (host, PORT))
    for _ in range(200):  # undecryptable: at most 20 'Dropped frame' lines per 10 s
        udp.sendto(b"\x00" * 10, (host, PORT))
    udp.close()
    time.sleep(1.0)
    text = log.since(pos)
    check("malformed key frames never log typed text", MARKER not in text,
          "marker absent from the log" if MARKER not in text else "MARKER FOUND in the log")
    check("unknown key name masked", "Error executing key [CTRL+?]" in text, "'key [CTRL+?]' error line")
    check("junk payload not echoed", "forged line" not in text, "no payload bytes in the log")
    longest = max((len(line) for line in text.splitlines()), default=0)
    check("no log line over 1,100 chars", longest <= 1100, f"longest {longest}")
    n = text.count("Dropped frame")
    check("drop messages rate-limited", 1 <= n <= 20, f"{n} 'Dropped frame' lines for 202 dropped datagrams")


def section_p(host, log, pw, clip):
    print("\n== P. password (rmauth1)")
    start = log.size()
    SECRETS.add(pw)
    addr = (host, PORT)
    yes = lambda b: "yes" if b else "NO"

    # P1: the SIN carries only the marker; chl is well formed
    try:
        sock, sin, chl = open_auth(host)
    except (OSError, ValueError) as e:
        check("P1 handshake and challenge", False, str(e))
        return
    sock.close()
    try:
        _, iterations, first_nonce = parse_chl(chl)
        chl_info = f"chl v1, iterations {iterations}"
    except ValueError:
        first_nonce, chl_info = None, None
    no_pw = pw.encode() not in sin + chl
    check("P1 handshake and challenge", sin.endswith(b" pwd @rmauth1 530") and no_pw and chl_info is not None,
          f"{sin!a}, {chl_info or 'chl malformed'}, password in the handshake: {'no' if no_pw else 'YES'}")

    # P3: a correct login, with a fresh server nonce per connection
    try:
        reply, a, proof_ok = login(host, pw)
    except (OSError, ValueError) as e:
        check("P3 correct login", False, str(e))
        return
    fresh = a.nonce_s != first_nonce
    if not check("P3 correct login", reply.startswith("ok ") and proof_ok and fresh,
                 f"cin {reply.split(' ')[0]!a}, proofS {'valid' if proof_ok else 'INVALID'}, new nonceS {yes(fresh)}"):
        a.sock.close()
        return

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # P4: tagged commands run, once each
        pos = log.size()
        udp.sendto(a.tagged(b"U", b"mos", "s 0"), addr)
        n_udp = settled_count(log, pos, "UDP")
        pos = log.size()
        a.sock.sendall(a.tagged(b"T", b"mos", "s 0"))
        n_tcp = settled_count(log, pos, "TCP")
        check("P4 tagged UDP and TCP commands run", n_udp == 1 and n_tcp == 1, f"UDP {n_udp}, TCP {n_tcp} 'mos s 0' lines (1 each)")

        # P5: untagged datagrams from this address, while its session is live, are dropped. Half
        # are padded so they are long enough to carry a tag (the padding fails as one).
        pos = log.size()
        for i in range(20):
            udp.sendto(frame(b"mos", "s 0" + " " * (16 if i % 2 else 0)), addr)
        n = settled_count(log, pos, "UDP", timeout=1.0)
        bad_tag = re.search(r"UDP dropped from \S+: bad tag", log.since(pos)) is not None
        check("P5 untagged UDP dropped", n == 0 and bad_tag, f"{n} lines for 20 frames, 'bad tag' drop line {yes(bad_tag)}")

        # P6: frames tagged with a random key are dropped
        pos = log.size()
        key = os.urandom(32)
        for _ in range(20):
            f = encrypt_chunk(command(b"mos", b"s 0"), 999)
            udp.sendto(f + tag(key, b"U", f), addr)
        n = settled_count(log, pos, "UDP", timeout=1.0)
        check("P6 wrong-key tags dropped", n == 0, f"{n} lines for 20 frames")

        # P7: a captured datagram replayed is dropped
        pos = log.size()
        d = a.tagged(b"U", b"mos", "s 0")
        udp.sendto(d, addr)
        settled_count(log, pos, "UDP")
        for _ in range(3):
            udp.sendto(d, addr)
        n = settled_count(log, pos, "UDP", want=2, timeout=1.0)
        replay = re.search(r"UDP dropped from \S+: replay", log.since(pos)) is not None
        check("P7 replayed datagram dropped", n == 1 and replay, f"{n} lines for 1 frame sent 4 times, 'replay' drop line {yes(replay)}")

        # P8: once the session is closed its key is dead
        a.sock.close()
        time.sleep(0.5)
        pos = log.size()
        udp.sendto(a.tagged(b"U", b"mos", "s 0"), addr)
        n = settled_count(log, pos, "UDP", timeout=1.0)
        check("P8 closed session's key rejected", n == 0, f"{n} lines")

        # P9: an untagged TCP frame after login closes the session (the second frame's start is
        # read as the first one's tag)
        _, b, _ = login(host, pw)
        pos = log.size()
        b.sock.sendall(frame(b"mos", "s 0") + frame(b"mos", "s 0"))
        closed = closed_within(b.sock, 2.0)
        b.sock.close()
        n = settled_count(log, pos, "TCP", timeout=0.5)
        check("P9 untagged TCP frame closes the session", closed and n == 0, f"closed {yes(closed)}, {n} lines")

        # P9b: a TCP frame whose chunk id does not increase (here: the same frame again) closes it
        _, c, _ = login(host, pw)
        pos = log.size()
        f = c.tagged(b"T", b"mos", "s 0")
        c.sock.sendall(f)
        settled_count(log, pos, "TCP")
        c.sock.sendall(f)
        closed = closed_within(c.sock, 2.0)
        c.sock.close()
        n = settled_count(log, pos, "TCP", want=2, timeout=0.5)
        check("P9b non-increasing TCP id closes the session", closed and n == 1, f"closed {yes(closed)}, {n} lines for 2 frames (1)")

        # P10: a command instead of ath is not run, and closes the connection
        sock, _, _ = open_auth(host)
        pos = log.size()
        sock.sendall(frame(b"mos", "s 0"))
        closed = closed_within(sock, 2.0)
        sock.close()
        n = settled_count(log, pos, "TCP", timeout=0.5)
        check("P10 command before ath closes", closed and n == 0, f"closed {yes(closed)}, {n} lines")

        # P16: a flood of silent connections can't keep the real tablet out: the oldest
        # unauthenticated ones are closed to admit new ones
        silent = []
        for _ in range(10):
            try:
                silent.append(socket.create_connection((host, PORT), timeout=2.0))
            except OSError:
                pass
        time.sleep(0.3)
        t0 = time.perf_counter()
        try:
            reply, h, proof_ok = login(host, pw)
            h.sock.close()
        except (OSError, ValueError) as e:
            reply, proof_ok = str(e), False
        dt = time.perf_counter() - t0
        check("P16 login while the pre-auth pool is flooded", reply.startswith("ok ") and proof_ok and dt <= 3.0,
              f"cin {reply.split(' ')[0]!a} in {dt * 1000:.0f} ms")
        closed = [closed_within(s, 0.2) for s in silent]
        close_all(silent)
        check("P16 oldest silent connections closed", len(silent) == 10 and closed[0] and closed.count(False) <= 2,
              f"{closed.count(True)}/{len(silent)} closed by the server")
        time.sleep(0.5)

        # P18: the pre-auth pool full of silent connections from 4 other addresses (2 each, the
        # per-address limit), reconnected as fast as the server closes them: a login from this
        # address that takes 1.5 s from challenge to ath still succeeds, because a full pool
        # gives up a connection of its busiest address, never simply the oldest one
        if host in ("127.0.0.1", "localhost"):
            flood = PreauthFlood([f"127.0.0.{i}" for i in range(2, 6)])
            flood.fill()
            opened = len(flood.socks)
            time.sleep(0.3)
            flood.start()
            try:
                reply, h, proof_ok = login(host, pw, pause=1.5)
                h.sock.close()
            except (OSError, ValueError) as e:
                reply, proof_ok = str(e), False
            finally:
                flood.done.set()
                flood.join(5.0)
            check("P18 pre-auth eviction spares a lone login", opened == 8 and reply.startswith("ok ") and proof_ok
                  and flood.reconnects > 0,
                  f"cin {reply.split(' ')[0]!a}, {opened}/8 flood connections opened, {flood.reconnects} reconnects, "
                  f"{flood.errors} connect errors")
            time.sleep(0.5)
        else:
            info("P18 pre-auth eviction", "skipped (needs host 127.0.0.1: it connects from 127.0.0.2-127.0.0.5)")

        # P17: an unauthenticated connection from the same address ending doesn't release a
        # button held by a logged-in session; that session ending does
        mouse = mouse_ready(host, clip, "P17 held button")
        if mouse is not None:
            _, holder, _ = login(host, pw)
            try:
                udp.sendto(holder.tagged(b"U", b"mos", "p 1"), addr)
                down = wait_until(mouse.left_down, 1.0)
                other, _, _ = open_auth(host)
                other.close()
                time.sleep(1.0)
                still = mouse.left_down()
                holder.sock.close()
                up = wait_until(lambda: not mouse.left_down(), 1.0)
                check("P17 only an authenticated session's end releases", down and still and up,
                      f"down {yes(down)}, still down after the other connection closed {yes(still)}, up after logout {yes(up)}")
            finally:
                holder.sock.close()
                unstick(mouse, lambda: None)  # the session is gone, so only a local button-up is left
    except (OSError, ValueError) as e:
        check("password session", False, f"error: {e}")
    finally:
        udp.close()

    # P11 + P12 together (both wait out the 10 s sign-in deadline): unframed bytes holding
    # a password in plain text are never logged, and a silent connection is closed
    try:
        s11, _, _ = open_auth(host)
        s12, _, _ = open_auth(host)
    except (OSError, ValueError) as e:
        check("P11/P12 connections", False, str(e))
    else:
        pwb = pw.encode()
        s11.sendall(b"pwd" + f"{len(pwb):03d}".encode() + pwb)
        t11, t12 = close_times([s11, s12], 15.0)
        close_all([s11, s12])
        check("P11 unframed password text closes", t11 is not None and pw not in log.since(start),
              f"closed after {t11:.1f} s" if t11 is not None else "still open after 15 s")
        check("P12 silent connection closed after 9-13 s", t12 is not None and 9 <= t12 <= 13,
              f"closed after {t12:.1f} s" if t12 is not None else "still open after 15 s")

    # P13, P14: wrong proofs fail, five in a row lock the address, and the lock ends
    try:
        reply, d, _ = login(host, pw, wrong=True)
        closed = closed_within(d.sock, 2.0)
        d.sock.close()
        check("P13 wrong proof fails and closes", reply == "fail" and closed, f"cin {reply!a}, closed {yes(closed)}")
        replies = []
        for _ in range(4):
            r, d, _ = login(host, pw, wrong=True)
            d.sock.close()
            replies.append(r)
        reply, d, _ = login(host, pw)
        d.sock.close()
        m = re.fullmatch(r"locked (\d+)", reply)
        wait = int(m.group(1)) if m else 0
        check("P14 five wrong proofs lock the address", replies == ["fail"] * 4 and 0 < wait <= 60,
              f"4 more wrong proofs got {replies}, then a correct proof got {reply!a}")
        if 0 < wait <= 60:
            info("lockout", f"waiting {wait + 1} s for it to end")
            time.sleep(wait + 1)
            reply, d, proof_ok = login(host, pw)
            d.sock.close()
            check("P14 correct login after the lock ends", reply.startswith("ok ") and proof_ok, f"cin {reply.split(' ')[0]!a}")
    except (OSError, ValueError) as e:
        check("wrong-password logins", False, f"error: {e}")

    # P15: no password, key, proof or nonce in the log; failures and the lock are logged
    time.sleep(0.5)
    text = log.since(start)
    leaked = sum(1 for s in SECRETS if s in text)
    check("P15 no password, key, proof or nonce logged", leaked == 0, f"{leaked} of {len(SECRETS)} secret values found")
    failed = re.search(r"Auth failed \[[^\]]*\] \(1 of 5\)", text) is not None
    locked = re.search(r"Auth locked \[[^\]]*\]", text) is not None
    check("P15 failures and the lock logged", failed and locked, f"'Auth failed [ip] (1 of 5)' {yes(failed)}, 'Auth locked [ip]' {yes(locked)}")


def section_g(pid):
    print("\n== G. leftover connections")
    time.sleep(1.5)
    try:
        out = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError) as e:
        info("netstat", f"failed: {e}")
        return
    states = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 5 and parts[0] == "TCP" and parts[1].endswith(f":{PORT}"):
            if pid is None or parts[4] == str(pid):
                states[parts[3]] = states.get(parts[3], 0) + 1
    owner = f"PID {pid}" if pid is not None else "any process"
    info("server sockets on :1978", f"{owner}: ESTABLISHED {states.get('ESTABLISHED', 0)}, "
                                    f"CLOSE_WAIT {states.get('CLOSE_WAIT', 0)} (expect ~0 each; a connected tablet adds 1)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("host", nargs="?", default="127.0.0.1")
    ap.add_argument("--paste", action="store_true", help="run section B (clipboard + Ctrl+V)")
    ap.add_argument("--capture", help="stress-capture.ps1 report (its -Out), to check pastes reached the window")
    ap.add_argument("--log", help="server log file, for UDP loss and log checks")
    ap.add_argument("--pid", type=int, help="server process id, for section G")
    ap.add_argument("--seed", type=int, default=None, help="fuzz seed (default: random)")
    ap.add_argument("--password", help="test password of a password-protected server: run only section P, then G")
    args = ap.parse_args()
    if args.password and not args.log:
        ap.error("--password needs --log")

    seed = args.seed if args.seed is not None else random.randrange(2**32)
    print(f"stress-test against {args.host}  (fuzz seed {seed})")
    log = None
    if args.log:
        log = LogTail(args.log)
        if log.size() == 0:
            print(f"warning: log {args.log!a} is missing or empty; log checks may FAIL")

    clip = None
    if args.paste and args.password:
        print("\n--paste: P17 holds the left button down in the 'AirKeyboard stress capture' window.", flush=True)
        clip = Clipboard()
    elif args.paste:
        print("""
==================================== PASTE MODE ====================================
 Section B sets THIS PC's clipboard and makes the server press Ctrl+V about 13 times
 (about 1 MB of text) and type into the window in front; section H holds the left
 button down in it. Bring the "AirKeyboard stress capture" window (stress-capture.ps1)
 to the front; every step waits until it is.
 Your current clipboard text is put back at the end.
====================================================================================""", flush=True)
        clip = Clipboard()

    started = time.perf_counter()
    if args.password:
        section_p(args.host, log, args.password, clip)
        section_g(args.pid)
    else:
        if not section_a(args.host, log):
            print("\nServer not reachable; stopping.")
            return 1
        if clip:
            section_b(args.host, log, clip, args.capture)
        else:
            print("\n== B. clipboard paste: skipped (needs --paste)")
        section_h(args.host, log, clip, args.capture)
        section_c(args.host, log)
        section_i(args.host, log)
        section_d(args.host)
        section_e(args.host)
        section_f(args.host, random.Random(seed))
        section_j(args.host, log)
        section_g(args.pid)

    failed = [name for name, ok in results if not ok]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed in {time.perf_counter() - started:.0f} s")
    for name in failed:
        print(f"  FAIL {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
