# Capture window for the stress runs (stress-test.py --paste and the Android StressTest).
# Like e2e-capture.ps1, but built for large pastes: the text box has no length limit, the
# JSON report is written at most every 2 s (and once more on close), and at most 20000
# events are stored. It also records when each typed character arrived ("arrivals": UTF-16
# code and Unix time in ms, for verify-stress.py --latency), the buttons still held ("None"
# when nothing is stuck) and when the capture ran (local time, as in the server log).
# Usage: powershell -ExecutionPolicy Bypass -File stress-capture.ps1 -Out report.json
# The Android StressTest runs about 5 minutes (drags, live typing, T5, churn, QR cycles).
param([string]$Out = "stress-report.json", [int]$Seconds = 420)

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$maxEvents = 20000
$state = @{ events = New-Object System.Collections.ArrayList; arrivals = New-Object System.Collections.ArrayList; dropped = 0; text = ""; start = ""; startedAt = ""; dirty = $true; lastWrite = [DateTime]::MinValue }
# DateTime.UtcNow ticks in ~15.6 ms steps here, too coarse for latency, so arrival times are a
# Unix-ms anchor taken once plus a Stopwatch (the anchor's own error is the same for every key)
$clock = [System.Diagnostics.Stopwatch]::StartNew()
$epochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
function Log($s) {
    if ($state.events.Count -ge $maxEvents) { $state.dropped += 1; return }
    $p = [System.Windows.Forms.Cursor]::Position
    [void]$state.events.Add("$s @$($p.X),$($p.Y)")
    $state.dirty = $true
}
function Arrive([char]$c) {
    if ($state.arrivals.Count -ge $maxEvents) { $state.dropped += 1; return }
    $ms = ($epochMs + $clock.Elapsed.TotalMilliseconds).ToString("F1", [System.Globalization.CultureInfo]::InvariantCulture)
    [void]$state.arrivals.Add("$([int]$c) $ms")
}
function WriteReport {
    $state.text = $tb.Text
    [ordered]@{ start = $state.start; startedAt = $state.startedAt; writtenAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff");
                buttons = [System.Windows.Forms.Control]::MouseButtons.ToString(); text = $state.text; events = $state.events;
                arrivals = $state.arrivals; dropped = $state.dropped } |
        ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 $Out
    $state.dirty = $false
    $state.lastWrite = Get-Date
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "AirKeyboard stress capture"
$form.Size = New-Object System.Drawing.Size(1400, 900)
$form.StartPosition = "CenterScreen"
$form.TopMost = $true
$form.KeyPreview = $true

$tb = New-Object System.Windows.Forms.TextBox
$tb.Multiline = $true
$tb.AcceptsReturn = $true
$tb.AcceptsTab = $true
$tb.MaxLength = 0  # unlimited; the default 32767 would truncate a large paste
$tb.Dock = "Fill"
$tb.Font = New-Object System.Drawing.Font("Segoe UI", 20)
$tb.ContextMenu = New-Object System.Windows.Forms.ContextMenu  # empty: right click must not open a menu
$form.Controls.Add($tb)

$tb.Add_MouseDown({ Log "down $($_.Button)" })
$tb.Add_MouseUp({ Log "up $($_.Button)" })
$tb.Add_MouseWheel({ Log "wheel $($_.Delta)" })
$tb.Add_TextChanged({ $state.dirty = $true })
$form.Add_KeyDown({ Log "key $($_.KeyData)" })
$form.Add_KeyPress({ Arrive $_.KeyChar })

$deadline = (Get-Date).AddSeconds($Seconds)
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 250
$timer.Add_Tick({
    # Rewriting a big report every tick makes the UI lag, so write at most every 2 s
    if ($state.dirty -and ((Get-Date) - $state.lastWrite).TotalSeconds -ge 2) { WriteReport }
    if ((Get-Date) -gt $deadline) { $form.Close() }
})

$form.Add_Shown({
    $form.WindowState = "Normal"  # launchers can pass a minimized start state
    $center = $form.PointToScreen((New-Object System.Drawing.Point(($form.ClientSize.Width / 2), ($form.ClientSize.Height / 2))))
    [System.Windows.Forms.Cursor]::Position = $center
    $state.start = "{0},{1}" -f $center.X, $center.Y
    $state.startedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
    $form.Activate()
    $tb.Focus()
    $timer.Start()
})
$form.Add_FormClosing({ $timer.Stop(); WriteReport })

[void]$form.ShowDialog()
