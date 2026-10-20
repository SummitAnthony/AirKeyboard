# Freezes (or with -Resume, unfreezes) a process: its sockets stay open but it answers nothing.
# Used with ReconnectTest#serverHangs (docs/TESTING.md). Usage: suspend-process.ps1 -ProcessId <pid> [-Resume]
param([int]$ProcessId, [switch]$Resume)
Add-Type -Name Nt -Namespace Win -MemberDefinition @'
[DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr handle);
[DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr handle);
'@
$p = Get-Process -Id $ProcessId
if ($Resume) { [Win.Nt]::NtResumeProcess($p.Handle) | Out-Null; "resumed $ProcessId" }
else { [Win.Nt]::NtSuspendProcess($p.Handle) | Out-Null; "suspended $ProcessId" }
