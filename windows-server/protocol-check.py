"""Loopback protocol check: discovery, SIN handshake, the heartbeat and one encrypted UDP command.

Usage: python protocol-check.py [host] [--exe PATH]
Sends the heartbeat "sys png" over TCP (the server must answer "png000" within 2 s), then
"mos s 0" (scroll zero notches), which the server logs but has no visible effect.
Covers the no-password mode only: against a password-protected server it says so and exits 2
(stress-test.py --password covers that mode).
--exe  the server exe: also runs "PATH --print-pairing" and checks every pairing QR code
       (address rules, a handshake on each address, the text art, and the decoded code
       when cv2 and numpy can be imported).
"""
import argparse
import random
import re
import socket
import struct
import subprocess
import sys
import time

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("host", nargs="?", default="127.0.0.1")
ap.add_argument("--exe", help="server exe, to check its --print-pairing output")
ARGS = ap.parse_args()
HOST = ARGS.host

OCTET = r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"  # no leading zeros, as the app's PairingCode
IPV4 = re.compile(rf"{OCTET}(?:\.{OCTET}){{3}}")
ART = {"█": (255, 255), "▀": (255, 0), "▄": (0, 255), " ": (0, 0)}  # (top, bottom) module, light = 255


def encrypt_chunk(payload: bytes, chunk_id: int) -> bytes:
    key = random.randint(1, 255)
    frame_key = bytes(random.randint(1, 255) for _ in range(8))
    ts = bytes(b ^ key for b in struct.pack("<d", time.time()))
    header = (
        b"\x23"
        + struct.pack("<fif", 3.0, chunk_id, 0.0)
        + ts
        + struct.pack("<ii", random.randint(0, 2**31 - 1), random.randint(0, 2**31 - 1))
        + bytes([key ^ 0x35])
        + bytes(k ^ key for k in frame_key)
    )
    assert len(header) == 38
    return header + bytes(p ^ frame_key[i % 8] for i, p in enumerate(payload))


def command(kind: str, cmd: str) -> bytes:
    data = cmd.encode()
    return kind.encode() + f"{len(data):03d}".encode() + data


def pairing_blocks(exe):
    """[(ip, art lines)] from the exe's --print-pairing output"""
    r = subprocess.run([exe, "--print-pairing"], capture_output=True, timeout=10)
    assert r.returncode == 0, f"--print-pairing exited {r.returncode}: {r.stderr.decode('utf-8', 'replace').strip()!a}"
    blocks, current = [], None
    for line in r.stdout.decode("utf-8-sig").splitlines():
        if IPV4.fullmatch(line):
            current = (line, [])
            blocks.append(current)
        elif not line:
            current = None
        else:
            assert current is not None, f"--print-pairing: unexpected line outside a QR block: {line!a}"
            current[1].append(line)
    return blocks


def decode(art):
    """The QR text decoded from the art with cv2, or None when cv2/numpy are missing"""
    try:
        import cv2
        import numpy as np
    except ImportError:
        return None
    rows = [[ART[c][half] for c in line] for line in art for half in (0, 1)]
    img = np.kron(np.array(rows, dtype=np.uint8), np.ones((10, 10), dtype=np.uint8))
    img = np.pad(img, 40, constant_values=255)
    text, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    return text


def check_pairing(exe):
    blocks = pairing_blocks(exe)
    assert 1 <= len(blocks) <= 3, f"--print-pairing printed {len(blocks)} QR blocks (want 1 to 3)"
    ips = [ip for ip, _ in blocks]
    assert len(set(ips)) == len(ips), f"pairing addresses repeat: {ips}"
    local = {ai[4][0] for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
    for ip, art in blocks:
        first, second = (int(o) for o in ip.split(".")[:2])
        assert 1 <= first <= 223 and first != 127 and (first, second) != (169, 254), f"not a pairing address: {ip}"
        assert ip in local, f"{ip} is not one of this PC's IPv4 addresses {sorted(local)}"
        with socket.create_connection((ip, 1978), timeout=3) as s:
            assert s.recv(1024)[:3] == b"SIN", f"no handshake on {ip}:1978"

        assert art, f"no QR art for {ip}"
        width = len(art[0])
        assert all(len(line) == width for line in art), f"QR art lines for {ip} differ in length"
        bad = {c for line in art for c in line} - set(ART)
        assert not bad, f"QR art for {ip} has other characters: {sorted(bad)!a}"
        assert all(line[0] == "█" and line[-1] == "█" for line in art), f"QR art for {ip} lacks the light border"
        assert width in (2 * len(art) - 1, 2 * len(art)), f"QR art for {ip} is not square ({width} x {2 * len(art)})"
        text = decode(art)
        assert text is None or text == ip, f"QR for {ip} decodes to {text!r}"
        print(f"pairing OK    : {ip} handshake" + (", QR decoded" if text else ", QR decode skipped (cv2 not available)"))


udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.settimeout(2)
udp.sendto(b"Hello", (HOST, 2008))
reply, addr = udp.recvfrom(1024)
assert reply[:2] == b"BC", reply
print(f"discovery OK  : {reply!r} from {addr[0]}")

tcp = socket.create_connection((HOST, 1978), timeout=3)
hs = tcp.recv(1024)
assert hs[:3] == b"SIN", hs
info = hs[6 : 6 + int(hs[3:6])].decode()
if re.search(r" pwd @rmauth1 \d+$", info):
    print("server is password-protected: this check covers the no-password mode (use stress-test.py --password)")
    sys.exit(2)
# The app splits this on spaces: a name with spaces must not shift the fields. The third
# field "@rm1" tells our app it is talking to this server (live typing then uses TCP)
fields = info.split(" ")
assert len(fields) == 4 and fields[1] == "nopwd" and fields[2] == "@rm1" and fields[3].isdigit(), f"handshake fields: {info!r}"
print(f"handshake OK  : {info!r}")

# A second client must be served while the first is still connected
tcp2 = socket.create_connection((HOST, 1978), timeout=3)
assert tcp2.recv(1024)[:3] == b"SIN", "no handshake for a second concurrent client"
print("second client : handshake OK while first is connected")
tcp2.close()

# Heartbeat: the app sends "sys png" every 5 s on its TCP session; the reply is the server
# message "png" with an empty body (and the server never logs it)
tcp.sendall(encrypt_chunk(command("sys", "png"), 1))
tcp.settimeout(2)
pong = b""
try:
    while len(pong) < 6:
        chunk = tcp.recv(6 - len(pong))
        if not chunk:
            break
        pong += chunk
except socket.timeout:
    pass
assert pong == b"png000", f"ping: expected b'png000' within 2 s, got {pong!r}"
print("ping OK       : sys png answered with png000")

udp.sendto(encrypt_chunk(command("mos", "s 0"), 1), (HOST, 1978))
print("command sent  : mos 's 0' via UDP 1978 (check server log for '[UDP ...] mos s 0')")
time.sleep(0.5)
tcp.close()

if ARGS.exe:
    check_pairing(ARGS.exe)
