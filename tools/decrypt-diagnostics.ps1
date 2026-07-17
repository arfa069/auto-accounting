#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
if ([System.IO.File]::Exists($resolvedOutput)) {
    throw "Output already exists: $resolvedOutput"
}

$javaCommand = Get-Command java -ErrorAction Stop
$helperPath = Join-Path $PSScriptRoot 'DecryptDiagnostics.java'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
    throw "Missing decryption helper: $helperPath"
}

$securePassphrase = Read-Host 'Diagnostic export passphrase' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassphrase)
$passphraseChars = $null
$passphraseBytes = $null
$encodedPassphraseChars = $null
try {
    $passphraseChars = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).ToCharArray()
    if ($passphraseChars.Length -lt 8) {
        throw 'Passphrase must contain at least 8 characters.'
    }
    $passphraseBytes = [System.Text.Encoding]::UTF8.GetBytes($passphraseChars)
    $encodedLength = [int]([Math]::Ceiling($passphraseBytes.Length / 3.0) * 4)
    $encodedPassphraseChars = New-Object 'char[]' $encodedLength
    [void][Convert]::ToBase64CharArray(
        $passphraseBytes,
        0,
        $passphraseBytes.Length,
        $encodedPassphraseChars,
        0
    )

    $quotedArguments = @($helperPath, $resolvedInput, $resolvedOutput) | ForEach-Object {
        '"' + $_.Replace('"', '\"') + '"'
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaCommand.Source
    $startInfo.Arguments = $quotedArguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw 'Unable to start the Java decryption helper.'
    }
    try {
        $process.StandardInput.Write($encodedPassphraseChars)
        $process.StandardInput.WriteLine()
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            $message = $stderr.Trim()
            if ([string]::IsNullOrWhiteSpace($message)) {
                $message = 'Decryption failed. Check the file and passphrase.'
            }
            throw $message
        }
        Write-Host $stdout.Trim()
    } finally {
        $process.Dispose()
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    if ($null -ne $passphraseChars) {
        [Array]::Clear($passphraseChars, 0, $passphraseChars.Length)
    }
    if ($null -ne $passphraseBytes) {
        [Array]::Clear($passphraseBytes, 0, $passphraseBytes.Length)
    }
    if ($null -ne $encodedPassphraseChars) {
        [Array]::Clear($encodedPassphraseChars, 0, $encodedPassphraseChars.Length)
    }
}
