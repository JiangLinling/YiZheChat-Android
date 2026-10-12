param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [string]$Alias = "yizhechat-production-2026"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Alias -notmatch '^[a-zA-Z0-9._-]{3,64}$') {
    throw "Alias must contain only letters, digits, dot, underscore, or hyphen."
}

$keytool = Get-Command keytool.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1
if (-not $keytool -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (Test-Path -LiteralPath $candidate) { $keytool = $candidate }
}
if (-not $keytool) {
    throw "A JDK keytool is required."
}

$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) {
    if (@(Get-ChildItem -LiteralPath $output -Force).Count -gt 0) {
        throw "Refusing to overwrite existing signing material at $output"
    }
} else {
    New-Item -ItemType Directory -Path $output | Out-Null
}
& icacls.exe $output /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" "SYSTEM:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to restrict the signing directory ACL." }

$passwordBytes = [byte[]]::new(48)
[Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
try {
    $password = [Convert]::ToBase64String($passwordBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
} finally {
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
}

$passwordFile = Join-Path $output "release-keystore.pass"
$keystore = Join-Path $output "yizhechat-release.p12"
$certificateFile = Join-Path $output "yizhechat-release-cert.pem"
[IO.File]::WriteAllText($passwordFile, $password, [Text.UTF8Encoding]::new($false))

& $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keystore `
    -storepass:file $passwordFile -keypass:file $passwordFile -alias $Alias `
    -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 9125 `
    -dname "CN=YiZheChat Production, OU=Android Release, O=YiZheChat, C=CN" `
    -ext "KeyUsage=digitalSignature"
if ($LASTEXITCODE -ne 0) { throw "Failed to generate the Android release signing identity." }

& $keytool -exportcert -rfc -storetype PKCS12 -keystore $keystore `
    -storepass:file $passwordFile -alias $Alias -file $certificateFile
if ($LASTEXITCODE -ne 0) { throw "Failed to export the Android release certificate." }

& $keytool -list -storetype PKCS12 -keystore $keystore -storepass:file $passwordFile -alias $Alias | Out-Null
if ($LASTEXITCODE -ne 0) { throw "The generated release keystore cannot be reopened." }

$certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem(
    [IO.File]::ReadAllText($certificateFile)
)
$fingerprint = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($certificate.RawData)
).ToLowerInvariant()
$manifest = [ordered]@{
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    alias = $Alias
    keystore_type = "PKCS12"
    certificate_sha256 = $fingerprint
    certificate_not_after_utc = $certificate.NotAfter.ToUniversalTime().ToString("o")
    keystore_file = "yizhechat-release.p12"
    password_file = "release-keystore.pass"
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "manifest.json") -Encoding utf8NoBOM
& icacls.exe $output /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" "SYSTEM:(OI)(CI)F" | Out-Null

$password = $null
[GC]::Collect()
Write-Output "Created the YiZheChat production Android signing identity."
Write-Output "Certificate SHA-256: $fingerprint"
Write-Output "Keystore and password remain only in: $output"
