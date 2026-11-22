<#
.SYNOPSIS
  Installs the newest Feeder Restock jar into the local simmc instance.

.DESCRIPTION
  Replaces any previous rail-restock / feeder-restock jar in the instance's mods
  folder with a freshly built one, so the instance never carries two versions.

  Windows keeps the jar locked while Minecraft runs, so the file swap has to wait
  for the game to exit: pass -Wait (intended to run as a background job) and the
  script parks until the process is gone, then installs.

.EXAMPLE
  tools\deploy.cmd

.EXAMPLE
  tools\deploy.cmd -Wait
#>
[CmdletBinding()]
param(
    [string]$Instance = 'D:\PCL2\simmc\.minecraft\versions\simmc',
    [string]$Jar,
    [switch]$Wait,
    [int]$PollSeconds = 3,
    [int]$TimeoutMinutes = 0
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $scriptDir

function Get-GameProcesses {
    $needle = $Instance.TrimEnd('\').ToLowerInvariant()
    $found = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($needle) })
    # A bare return would unroll a single match into a scalar whose .Count is null
    # under Windows PowerShell, so keep it an array on purpose.
    return ,$found
}

function Resolve-Jar {
    if ($Jar) {
        if (-not (Test-Path -LiteralPath $Jar)) { throw "Jar not found: $Jar" }
        return (Resolve-Path -LiteralPath $Jar).Path
    }
    $buildJars = @(Get-ChildItem (Join-Path $project 'build\libs') -Filter 'feeder-restock-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-sources.jar' } |
        Sort-Object LastWriteTime -Descending)
    if ($buildJars.Count -gt 0) { return $buildJars[0].FullName }
    $distJars = @(Get-ChildItem (Join-Path $project 'dist') -Recurse -Filter 'feeder-restock-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-sources.jar' -and $_.DirectoryName -notmatch '\\archive\\' } |
        Sort-Object LastWriteTime -Descending)
    if ($distJars.Count -gt 0) { return $distJars[0].FullName }
    throw 'No installable jar found; run build.cmd build first.'
}

$jarPath = Resolve-Jar
$mods = Join-Path $Instance 'mods'
if (-not (Test-Path -LiteralPath $mods)) { throw "Instance mods folder not found: $mods" }

$running = Get-GameProcesses
if ($running.Count -gt 0) {
    $pids = ($running | ForEach-Object { $_.ProcessId }) -join ', '
    if (-not $Wait) {
        Write-Host "Minecraft is running (PID $pids); nothing installed. Use -Wait to queue the deployment."
        exit 2
    }
    Write-Host "Minecraft is running (PID $pids); waiting for it to exit before installing $((Split-Path -Leaf $jarPath))"
    $deadline = $null
    if ($TimeoutMinutes -gt 0) { $deadline = (Get-Date).AddMinutes($TimeoutMinutes) }
    while ((Get-GameProcesses).Count -gt 0) {
        if ($deadline -and (Get-Date) -gt $deadline) { throw 'Timed out waiting for Minecraft to exit; nothing installed.' }
        Start-Sleep -Seconds $PollSeconds
    }
    # Give the JVM a moment to release the jar file handles.
    Start-Sleep -Seconds 2
}

function Invoke-WithRetry {
    param([string]$What, [scriptblock]$Action, [int]$Attempts = 10, [int]$DelaySeconds = 3)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            & $Action
            return
        } catch {
            if ($attempt -eq $Attempts) { throw }
            Write-Host "$What failed (attempt $attempt): $($_.Exception.Message); retrying in $DelaySeconds s"
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

$old = @(Get-ChildItem -LiteralPath $mods -Filter '*.jar' |
    Where-Object { $_.Name -match '^(rail-restock|feeder-restock)-' })
foreach ($file in $old) {
    Invoke-WithRetry -What "removing $($file.Name)" -Action { Remove-Item -LiteralPath $file.FullName -Force }
    Write-Host "removed $($file.Name)"
}

$target = Join-Path $mods (Split-Path -Leaf $jarPath)
Invoke-WithRetry -What "copying $((Split-Path -Leaf $jarPath))" -Action { Copy-Item -LiteralPath $jarPath -Destination $target -Force }

$sourceHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
$targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
if ($sourceHash -ne $targetHash) { throw "Post-copy checksum mismatch: $target" }

$version = 'unknown'
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($target)
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'fabric.mod.json' }
    if ($entry) {
        $reader = New-Object System.IO.StreamReader($entry.Open())
        $meta = $reader.ReadToEnd()
        $reader.Close()
        $match = [regex]::Match($meta, '"version"\s*:\s*"([^"]+)"')
        if ($match.Success) { $version = $match.Groups[1].Value }
    }
    $zip.Dispose()
} catch {
    # Only used for the message; the copy above already succeeded.
}

Write-Host "installed feeder-restock $version -> $mods"
Write-Host "sha256 $sourceHash"
