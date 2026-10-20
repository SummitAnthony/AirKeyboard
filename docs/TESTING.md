# Testing AirKeyboard

These tests need a Windows PC running `AirKeyboard.exe` and, for the device tests, an Android
tablet connected over adb (wireless debugging works). Commands are PowerShell, run from the repo
root unless noted.

All of these take over the PC's mouse and keyboard while they run; leave the PC alone. Never use
`gradlew connectedAndroidTest` (it uninstalls the app). Delete report `.json` files and
`latency-send.txt` afterwards; they contain test text.

**Focus gotcha:** `stress-test.py --paste` needs its capture window in front, and editors or
chat apps can take focus back. Run `windows-server\front-click.ps1` (clicks the window centre)
in the same PowerShell command right before `stress-test.py`. Don't use an Alt-key trick (it
swallows the first Ctrl+V). Instrumentation tests don't need it.

The tablet ROM used so far blocks `adb shell input`, so automated touches go through
instrumentation tests. `adb shell svc power stayon true` keeps the screen on during tests. If
instrumentation hangs with "failed to attach", kill it and retry.

1. **Unit tests** (in `android-app`): `.\gradlew.bat testDebugUnitTest` (AuthCryptoTest,
   HandshakeParseTest, HeartbeatTest, PairingCodeTest, ChunkBuilderTest, ServerRepliesTest,
   Utf8SplitTest, BookmarkStoreTest, ConnectionHistoryTest, LiveTypingTest).
2. **Protocol only:** server running, `python -I windows-server\protocol-check.py --exe <path to AirKeyboard.exe>`.
   Its "ping OK" step sends `sys png` over TCP and expects a `png000` reply within 2 s.
   (`stress-test.py` without `--paste` is also safe on a PC in use; its section A checks that a
   UDP datagram sent three times runs once.)
3. **PC stress (no password):**
   ```
   powershell -ExecutionPolicy Bypass -File windows-server\stress-capture.ps1 -Out pc-stress.json
   powershell -ExecutionPolicy Bypass -File windows-server\front-click.ps1; python -I windows-server\stress-test.py --paste --capture pc-stress.json --log <server log> --pid <server pid>
   ```
4. **End to end on the tablet** (build and install in `android-app`):
   ```
   .\gradlew.bat assembleDebug assembleDebugAndroidTest
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
   powershell -ExecutionPolicy Bypass -File windows-server\e2e-capture.ps1 -Out report.json
   adb shell am instrument -w -e class com.airkeyboard.EndToEndTest -e qr <PC LAN IP> com.airkeyboard.test/androidx.test.runner.AndroidJUnitRunner
   python -I windows-server\verify-e2e.py report.json --log <server log>
   ```
   To test the signed release build instead: `.\gradlew.bat assembleRelease assembleReleaseAndroidTest -PtestBuildType=release`
   and install `apk\release\app-release.apk` and `apk\androidTest\release\app-release-androidTest.apk`
   (uninstall the debug build first: different signature; `run-as` doesn't work on a release build).
5. **Stress on the tablet** (includes drags and the T5 latency test):
   ```
   powershell -ExecutionPolicy Bypass -File windows-server\stress-capture.ps1 -Out report-stress.json
   adb logcat -c
   $lat = Start-Process adb -ArgumentList 'logcat -v raw -s StressLatency:I' -NoNewWindow -PassThru -RedirectStandardOutput latency-send.txt
   adb shell am instrument -w -e class com.airkeyboard.StressTest com.airkeyboard.test/androidx.test.runner.AndroidJUnitRunner
   Stop-Process -Id $lat.Id
   ```
   (The send log is read while the test runs: logcat's buffer is small, and a chatty device can
   push the lines out before the test ends.)
   ```
   python -I windows-server\verify-stress.py report-stress.json --log <server log> --latency latency-send.txt
   ```
6. **Password mode:** stop the server; `$env:AIRKEYBOARD_CONFIG = "$env:TEMP\airkeyboard-test-config.json"`;
   `Start-Process AirKeyboard.exe -ArgumentList '--set-password' -NoNewWindow -Wait` (type it; don't
   pipe it from PowerShell 5.1); start the server with `--console` and the same env var; then `stress-test.py --password <pw> --log <log> --pid <pid>`
   (its P18 step fills the pre-auth pool from 127.0.0.2 to 127.0.0.5 and checks that a login
   from 127.0.0.1 still succeeds), EndToEndTest (both `fullSession` and `passwordSession`, each
   with its own capture window: verify-e2e only knows `fullSession`'s input) and StressTest with
   `-e password <pw>`, plus a logcat grep for the password/test text. Delete the temp config
   afterwards and re-run protocol-check.
7. **Losing the PC** (`ReconnectTest`, sends no input): run one method at a time, e.g.
   `adb shell am instrument -w -e qr <PC LAN IP> -e class com.airkeyboard.ReconnectTest#serverRestart ...`
   (GboardBackspaceTest, PinchTest and SmokeTest also need `-e qr <PC LAN IP>`). Each
   connects, opens the keyboard screen and logs `RECONNECT_READY <name>` (`adb logcat -s ReconnectTest`).
   Then, on the PC: for `serverRestart` stop the console server and start it again about 3 s
   later; for `serverHangs` freeze it with `windows-server\suspend-process.ps1 -ProcessId <pid>`
   and unfreeze it (`-Resume`) once the test logs `LOST hang`. The app must reconnect on its own
   with no old screen left (about 8 s and 14 s on the test tablet).

Report files written to the repo root (`*.json`, `latency-send.txt`) are gitignored, but delete
them anyway.

## Running the server for tests

`AirKeyboard.exe` is a GUI-subsystem app: with no arguments it starts the tray app. Tests use the
console server: `Start-Process .\windows-server\bin\Debug\net8.0-windows\AirKeyboard.exe -ArgumentList '--console' -NoNewWindow`
(logs go to `logs\` under the current folder). PowerShell doesn't wait for a GUI-subsystem exe, so
use `Start-Process ... -Wait` or pipe the output (`| Out-String`) for the other CLI flags; Python's
`subprocess` waits normally.
