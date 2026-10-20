param([string]$Title = "AirKeyboard stress capture")
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class FC {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
}
"@
$p = Get-Process | Where-Object { $_.MainWindowTitle -like "*$Title*" } | Select-Object -First 1
if (-not $p) { "window not found"; exit 1 }
$r = New-Object FC+RECT
[void][FC]::GetWindowRect($p.MainWindowHandle, [ref]$r)
$x = [int](($r.L + $r.R) / 2); $y = [int](($r.T + $r.B) / 2)
[void][FC]::SetCursorPos($x, $y)
[FC]::mouse_event(0x02, 0, 0, 0, [UIntPtr]::Zero); [FC]::mouse_event(0x04, 0, 0, 0, [UIntPtr]::Zero)  # left click
Start-Sleep -Milliseconds 400
$sb = New-Object System.Text.StringBuilder 256
[void][FC]::GetWindowText([FC]::GetForegroundWindow(), $sb, 256)
"clicked at $x,$y; foreground: $sb"
if ($sb.ToString() -notlike "*$Title*") { exit 1 }
