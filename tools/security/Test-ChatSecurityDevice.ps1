param(
    [string]$Serial = "192.168.101.29:37931",
    [string]$PackageName = "com.yizhe.chat.debug",
    [string]$ApkPath = "",
    [switch]$SkipNavigation
)

$ErrorActionPreference = "Stop"

$adbCandidates = @()
if ($env:LOCALAPPDATA) { $adbCandidates += Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }
$adbCandidates = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ }
$adb = $adbCandidates | Select-Object -First 1
if (-not $adb) { throw "adb.exe was not found." }

function Invoke-Adb {
    param([string[]]$AdbArguments)
    & $adb -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($AdbArguments -join ' ')" }
}

function Get-UiXml {
    Invoke-Adb -AdbArguments @("shell", "uiautomator", "dump", "/sdcard/yizhe-window.xml") | Out-Null
    return (Invoke-Adb -AdbArguments @("exec-out", "cat", "/sdcard/yizhe-window.xml")) -join "`n"
}

function Click-UiText {
    param([string]$Text)
    $xml = Get-UiXml
    $escaped = [Regex]::Escape($Text)
    $match = [Regex]::Match($xml, "<node[^>]*(?:text|content-desc)=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $match.Success) {
        $match = [Regex]::Match($xml, "<node[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"[^>]*(?:text|content-desc)=`"$escaped`"")
    }
    if (-not $match.Success) { throw "UI text not found: $Text" }
    if ([int]$match.Groups[3].Value -eq 0 -and [int]$match.Groups[4].Value -eq 0) {
        $prefixStart = [Math]::Max(0, $match.Index - 2000)
        $prefix = $xml.Substring($prefixStart, $match.Index - $prefixStart)
        $ancestorMatches = [Regex]::Matches(
            $prefix,
            "<node[^>]*clickable=`"true`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
        )
        if ($ancestorMatches.Count -gt 0) { $match = $ancestorMatches[$ancestorMatches.Count - 1] }
    }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-Adb -AdbArguments @("shell", "input", "tap", [string][int]$x, [string][int]$y) | Out-Null
    Start-Sleep -Milliseconds 800
}

& $adb connect $Serial | Out-Host
if ((Invoke-Adb -AdbArguments @("get-state")) -notcontains "device") { throw "Device is not ready: $Serial" }

if ($ApkPath) {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    Invoke-Adb -AdbArguments @("install", "-r", $resolvedApk) | Out-Host
}

Invoke-Adb -AdbArguments @("shell", "monkey", "-p", $PackageName, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 2

if (-not $SkipNavigation) {
    Click-UiText "我"
    Click-UiText "聊天与安全"
}

$pageSources = New-Object System.Collections.Generic.List[string]
1..4 | ForEach-Object {
    $pageSources.Add((Get-UiXml))
    Invoke-Adb -AdbArguments @("shell", "input", "swipe", "540", "1700", "540", "650", "350") | Out-Null
    Start-Sleep -Milliseconds 500
}
$allUi = $pageSources -join "`n"

$required = @(
    "聊天与安全", "设备安全验证", "恢复历史消息密钥", "消息显示规则",
    "消息通知", "麦克风和相机", "通话提醒", "勿扰模式权限",
    "后台运行", "自启动", "后台数据", "锁屏显示", "位置采集边界"
)
$missing = $required | Where-Object { $allUi -notmatch [Regex]::Escape($_) }
$forbidden = @("等待管理员审批", "请管理员批准", "管理员拒绝了设备", "联系管理员处理") |
    Where-Object { $allUi -match [Regex]::Escape($_) }

$packageDump = (Invoke-Adb -AdbArguments @("shell", "dumpsys", "package", $PackageName)) -join "`n"
$diagnostics = (Invoke-Adb -AdbArguments @("logcat", "-d", "-v", "brief", "-s", "YiZheProtectedContent")) -join "`n"

[pscustomobject]@{
    Serial = $Serial
    Package = $PackageName
    RequiredUiItems = $required.Count
    MissingUiItems = ($missing -join ", ")
    ForbiddenUiText = ($forbidden -join ", ")
    NotificationsGranted = $packageDump -match "android.permission.POST_NOTIFICATIONS: granted=true"
    MicrophoneGranted = $packageDump -match "android.permission.RECORD_AUDIO: granted=true"
    CameraGranted = $packageDump -match "android.permission.CAMERA: granted=true"
    ProtectedDiagnosticCount = ([Regex]::Matches($diagnostics, "YZCP_")).Count
} | Format-List

if ($missing.Count -gt 0) { throw "Missing chat-security UI items: $($missing -join ', ')" }
if ($forbidden.Count -gt 0) { throw "Forbidden administrator wording is visible: $($forbidden -join ', ')" }
