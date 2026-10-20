# AirKeyboard protocol

AirKeyboard's protocol: discovery, the handshake, encrypted chunk frames, the `mos` and `key`
commands, a password login (`rmauth1`), paste on PC (`clp`) and system commands (`sys`).
AirKeyboard is made and tested only with itself; using it with other apps or servers isn't
supported.

Code: server `windows-server/` (`RemoteControlServer.cs` connections, handshake and login;
`CommandProcessor.cs` commands; `EncryptionUtil.cs` frames; `InputController.cs` key names;
`DiscoveryListener.cs`; `AuthSession.cs`, `AuthLimiter.cs`, `AuthConfig.cs`; `HeldButtons.cs`;
`PasteTransfer.cs`). Client `android-app/.../network/` (`AirKeyboardClient.kt`,
`ChunkBuilder.kt`, `encryption/EncryptionUtil.kt`, `AuthCrypto.kt`, `PairingCode.kt`,
`discovery/ServerDiscovery.kt`).

Integers and floats in frame headers are little-endian. "Length" fields are always 3 ASCII
decimal digits (`000` to `999`) counting UTF-8 **bytes**, not characters.

## Ports

| Port | Transport | Use |
|---|---|---|
| 2008 | UDP | Discovery |
| 1978 | TCP | Handshake, login, the session; reliable commands; server replies |
| 1978 | UDP | Commands (mouse, most keys) |

The firewall rules AirKeyboard creates allow these inbound from the local subnet only.

## Discovery

The client broadcasts the 5 ASCII bytes `Hello` to UDP 2008 every 300 ms. The server answers
the sender (unicast) with:

```
"BC" + length + name
```

`name` is the PC name (`%AIRKEYBOARD_NAME%` or the computer name). The server never
answers a broadcast, multicast or port-0 source, and answers each address at most every 100 ms.

## Pairing QR code

The QR code holds only the PC's IPv4 address, e.g. `192.168.0.100` (port 1978). The app also
accepts `192.168.0.100:1978`.
Hostnames are rejected, so scanning a code never causes a DNS lookup. The QR code never carries a
password.

## Session and handshake

1. The client opens TCP 1978.
2. The server immediately sends (plain, not framed):

   ```
   "SIN" + length + "<name> <nopwd|pwd> <pwdfield> <version>"
   ```

   - AirKeyboard, no password: `<name> nopwd @rm1 530`
   - AirKeyboard, password: `<name> pwd @rmauth1 530`, followed in the same write by a `chl`
     message (see [Password login](#password-login-rmauth1)).
   - The app refuses any other handshake: a `<pwdfield>` that doesn't start with `@`, or a
     password login it doesn't know.

   Whitespace in the PC name is replaced by `_`. The client parses the fields from the right, so
   it also copes with names that contain spaces.
3. The TCP connection stays open for the whole session; either side closing it ends the session.
   The server keeps it idle (no read timeout after login) and enables TCP keepalive; both ends
   set TCP_NODELAY.

Commands are chunk frames, sent as UDP datagrams to port 1978 and/or written to the TCP session.

## Chunk frame

38-byte header, then the payload XORed with an 8-byte key.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x23` (`#`) |
| 1 | 4 | float32 protocol version, `3.0` |
| 5 | 4 | int32 chunk id; the client counts up from 0 per session |
| 9 | 4 | float32 seconds since the client's previous frame |
| 13 | 8 | float64 Unix time in seconds, each byte XOR `k` (not checked: clocks drift) |
| 21 | 4 | int32 random |
| 25 | 4 | int32 random |
| 29 | 1 | `k XOR 0x35` |
| 30 | 8 | `key[i] XOR k` |
| 38 | n | `payload[i] XOR key[i mod 8]` |

`k` and the 8 key bytes are random, 1 to 255, new for every frame. This is obfuscation, not
encryption: anyone who captures a frame can decode it.

Wi-Fi now and then delivers a UDP datagram twice. Since every frame has a new chunk id, a
datagram byte-identical to one received from the same address in the last 2 s is such a copy:
without a password the server drops it (with a password the replay window does), so a click or
letter never runs twice.

The decoded payload is:

```
type (3 ASCII chars) + length + body (length bytes)
```

On TCP the server reads the 38-byte header, the 6-byte type + length, then exactly `length` body
bytes, so frames need no other delimiter. A bad header byte or a non-digit length closes the
connection. On UDP each datagram is one frame (at least 44 bytes).

In password mode every frame is followed by a 16-byte tag (see below).

## Client commands

### `mos`: mouse (UDP or TCP)

| Body | Meaning |
|---|---|
| `m <dx> <dy>` | Move the pointer by dx, dy pixels (relative) |
| `c <b>` | Click button b |
| `d <b>` | Double-click |
| `p <b>` | Press and hold (drag start) |
| `r <b>` | Release |
| `s <n>` | Scroll n wheel notches; positive is up |
| `z <n>` | Zoom n notches (Ctrl + wheel); positive zooms in. Our server only: the app sends it only when the handshake marks the server as ours |

`b`: `1` left, `2` right, `3` middle.

A held button belongs to the address that pressed it. The server releases it on `r`, when that
address's session ends, when the server stops, or after 5 s without a `mos` command from that
address (the app sends `m 0 0` every second while a drag is held still). The app sends each
release three times over UDP (0, 40 and 120 ms) because a lost release would leave the button
down; extra releases are harmless.

### `key`: keyboard (UDP or TCP)

| Body | Meaning |
|---|---|
| `t<text>` | Type text as Unicode (independent of the PC's keyboard layout); `\n` presses Enter |
| `[NAME]` | Press and release one key |
| `[A+B+C]` | Combo: press in order, release in reverse, e.g. `[CTRL+SHIFT+T]` |

The app splits long text into frames of at most 200 characters (never between a surrogate pair).

Key names (case-insensitive):

- Letters and digits: `A` to `Z`, `0` to `9`
- Function keys: `F1` to `F24`
- Editing and navigation: `ENTER` (`RETURN`), `BACKSPACE`, `TAB`, `ESCAPE` (`ESC`), `SPACE`,
  `DELETE`, `INSERT`, `HOME`, `END`, `PAGEUP`, `PAGEDOWN`, `LEFT`, `UP`, `RIGHT`, `DOWN`,
  `PRINTSCREEN`
- Modifiers: `SHIFT`, `CTRL` (`CONTROL`), `ALT`, `WIN`
- Media and volume: `VOLUME_MUTE`, `VOLUME_DOWN`, `VOLUME_UP`, `MEDIA_NEXT`, `MEDIA_PREVIOUS`,
  `MEDIA_STOP`, `MEDIA_PLAY_PAUSE`
- Browser: `BROWSER_BACK`, `BROWSER_FORWARD`, `BROWSER_REFRESH`
- Numpad and locks (added for AirKeyboard): `NUMPAD0` to `NUMPAD9`, `MULTIPLY`, `ADD`,
  `SUBTRACT`, `DECIMAL`, `DIVIDE`, `NUMLOCK`, `CAPSLOCK`

A combo with an unknown name does nothing.

### `clp`: paste on PC (TCP only, AirKeyboard only)

| Body | Meaning |
|---|---|
| `b` | Begin a transfer (discards any unfinished one) |
| `d<bytes>` | Append raw UTF-8 bytes (at most 998, so `d` + bytes fits 999) |
| `e` | End: decode, put the text on the clipboard, press Ctrl+V |

Slices may split a UTF-8 sequence; the server decodes only at `e` (strictly: invalid UTF-8 is
dropped). The client cuts at code-point boundaries anyway. A transfer over 1 MiB is discarded.
Line endings become CRLF. The clipboard entry is marked to stay out of Windows clipboard history
and cloud sync. `clp` over UDP is ignored.

After a paste the client sends its next commands over TCP for 8 s, so none of them can overtake
the paste's Ctrl+V (the server runs TCP and UDP input on different threads with no ordering
between them).

### `sys`: system commands (TCP only, AirKeyboard only)

The body is ASCII text:

| Body | Meaning |
|---|---|
| `pwr shutdown` | Shut down the PC |
| `pwr restart` | Restart |
| `pwr sleep` | Sleep |
| `pwr logoff` | Sign out the current user |
| `pwr lock` | Lock the PC |
| `url <URL>` | Open the URL in the PC's default browser; only `http://` and `https://` URLs, up to about 995 bytes (one frame) |
| `win list` | Ask for the list of open app windows (reply `wnl` ... `wne`) |
| `win focus <id>` | Bring that window to the front (`<id>` from `win list`) |
| `win close <id>` | Ask that window to close (the app in it may still ask to save) |
| `clp get` | Ask for the PC's clipboard text (reply `cbb` ... `cbe`) |
| `png` | Heartbeat ping (reply `png`, empty body) |

The app sends `sys` frames only when the handshake marked the server as AirKeyboard (`@`). The
app asks for confirmation before any `pwr` command.

### Heartbeat

The app sends `sys` `png` right after connecting and then every 5 s. The server answers with the message `png000` (marker `png`, empty body) and
never logs the ping.

The app treats the PC as gone when a ping was written more than 10 s ago and no server message
of any kind has arrived since that write began. It enforces this only after it has received at
least one `png` reply in the session, so an older AirKeyboard server that doesn't answer pings
never causes a disconnect. After a gap of more than 10 s between its checks (the tablet slept,
or a long paste kept the sender busy) the 10 s window starts again. A lost PC is noticed after
about 10-20 s (about 14 s in tests).

### `ath`: login

See below. An `ath` frame after the login does nothing.

### Transport the app uses

- Moves, clicks, scrolls and drags: UDP (moves may be dropped if the send queue backs up).
- Live typing and other reliable key frames: the TCP session.
- `clp` and `sys`: always the TCP session.

## Server messages

Everything the server sends goes over the TCP session as plain (not framed, not XORed, untagged)
messages:

```
marker (3 ASCII chars) + length + body
```

| Marker | Body | When |
|---|---|---|
| `SIN` | handshake fields | on connect |
| `chl` | `1 <salt> <iterations> <nonceS>` | password mode, right after `SIN` |
| `cin` | `ok <proofS>` / `fail` / `locked <seconds>` | answer to `ath` |
| `wnl` | `<id>\t<title>` | one per window, front-most first, answer to `win list` |
| `wne` | empty | end of the window list |
| `cbb` | empty | clipboard text follows, answer to `clp get` |
| `cbd` | UTF-8 bytes, at most 900 per message | a slice of the clipboard text |
| `cbe` | empty, or `toolarge` | end of the clipboard text; `toolarge` when it is over 1 MiB |
| `png` | empty | answer to `sys png` (see [Heartbeat](#heartbeat)) |

`<id>` is an opaque token; the client sends it back unchanged in `win focus` / `win close`. A
window title must fit the 3-digit length, so long titles are shortened. The client joins the
`cbd` bytes and decodes them only after `cbe` (a slice may end mid code point). The client gives
up on a `win list` or `clp get` reply after 5 s.

## Password login (rmauth1)

The password is set on the PC (minimum 8 characters). The PC stores a salt, the iteration count
and the derived key K, never the password:

```
K = PBKDF2-HMAC-SHA256(UTF-8(NFC(password)), salt, iterations, 32 bytes)
```

1. Server: `SIN ... pwd @rmauth1 530` and `chl` `1 <salt b64> <iterations> <nonceS b64>` (salt
   and nonceS 16 bytes each, standard base64 with padding, iterations 100000). The client accepts
   only version `1`, 16-byte values and 100,000 to 1,000,000 iterations, so a fake PC can't make
   offline guessing cheaper or freeze the app.
2. Client: its first frame, on TCP, chunk id 0, untagged: `ath` `1 <nonceC b64> <proofC b64>`.
3. Server: `cin ok <proofS b64>`, or `cin fail` / `cin locked <seconds>` and it closes the
   connection. The client checks proofS, so it knows the PC really has K.

With `n = nonceS || nonceC`:

```
proofC = HMAC-SHA256(K, "rmauth1 client" || n)
proofS = HMAC-SHA256(K, "rmauth1 server" || n)
SKmac  = HMAC-SHA256(K, "rmauth1 mac"    || n)
```

After `cin ok`, every client frame on TCP and UDP is sent as

```
frame || HMAC-SHA256(SKmac, T || frame)[0..16)        T = 'T' on TCP, 'U' on UDP
```

where `frame` is the exact bytes of the chunk frame (header + XORed payload). Chunk ids continue
from 1. Replays are rejected: on TCP ids must strictly increase, on UDP a 64-wide sliding window
accepts each id once. A bad tag or replay closes the TCP connection; a bad UDP datagram is
dropped. A UDP datagram counts only if a logged-in session from the sender's address made its
tag.

Limits: the login must finish within 10 s of connecting. Connections that have not logged in
share their own pool (8 in all, 2 per address), so they can't take a logged-in session's place.
When the pool is full, the address holding the most waiting connections loses its oldest one
(ties: the oldest overall), so a flood from up to 7 other addresses can't keep the tablet out
(with 8 or more, each holding one connection, it still can while the flood lasts). Per
address, 5 failed or malformed logins lock it out for 30 s, doubling with each further failure up
to 10 minutes; the server forgets an address's failures 1 hour after its last failure or the end
of its lock. There is no global lockout. A locked address gets `cin locked <seconds>` without its
proof being checked.

What this does and doesn't give: other devices can't send input without the password, and can't
replay captured frames. The traffic is still only XOR-obfuscated, so typed and pasted text is
readable to anyone capturing it.

## Connection limits (no password)

At most 32 TCP sessions and 4 per address; a 5th from the same address replaces that address's
oldest (a tablet reconnecting after a Wi-Fi drop is never locked out). At the global cap, the
address holding the most sessions loses its oldest. Loopback connections (local test tools)
never replace others and are refused at the cap.
