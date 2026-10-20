# Capture window for the Android end-to-end test (app/src/androidTest/.../EndToEndTest.kt).
# Opens a topmost text box under the cursor and records every button press and release,
# wheel, key and the final text, writing a JSON report to $Out until $Seconds pass. The report
# also holds the buttons still held ("None" when nothing is stuck) and when the capture ran
# (local time, as in the server log), so verify-e2e.py --log can check only this run's lines.
# Usage: powershell -ExecutionPolicy Bypass -File e2e-capture.ps1 -Out report.json
param([string]$Out = "e2e-report.json", [int]$Seconds = 240)

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$state = @{ events = New-Object System.Collections.ArrayList; text = ""; start = ""; ticks = 0; startedAt = "" }
function Log($s) {
    $p = [System.Windows.Forms.Cursor]::Position
    [void]$state.events.Add("$s @$($p.X),$($p.Y)")
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "AirKeyboard E2E capture"
$form.Size = New-Object System.Drawing.Size(1400, 900)
$form.StartPosition = "CenterScreen"
$form.TopMost = $true
$form.KeyPreview = $true

$tb = New-Object System.Windows.Forms.TextBox
$tb.Multiline = $true
$tb.AcceptsReturn = $true
$tb.AcceptsTab = $true
$tb.Dock = "Fill"
$tb.Font = New-Object System.Drawing.Font("Segoe UI", 20)
$tb.ContextMenu = New-Object System.Windows.Forms.ContextMenu  # empty: right click must not open a menu
$form.Controls.Add($tb)

$tb.Add_MouseDown({ Log "down $($_.Button)" })
$tb.Add_MouseUp({ Log "up $($_.Button)" })
$tb.Add_MouseWheel({ Log "wheel $($_.Delta)" })
$form.Add_KeyDown({ Log "key $($_.KeyData)" })

$deadline = (Get-Date).AddSeconds($Seconds)
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 250
$timer.Add_Tick({
    $state.text = $tb.Text
    [ordered]@{ start = $state.start; startedAt = $state.startedAt; writtenAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff");
                buttons = [System.Windows.Forms.Control]::MouseButtons.ToString(); text = $state.text; events = $state.events } |
        ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 $Out
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

[void]$form.ShowDialog()
