<#
.SYNOPSIS
    Sets up a Windows machine for ChapterPlayer development with Claude Code.

.DESCRIPTION
    Installs and configures: Git, GitHub CLI, Android Studio, the Android SDK
    (platforms, build tools, emulator, Android Auto Desktop Head Unit), an
    emulator image + AVD, Claude Code, environment variables, and clones the repo.

    Safe to re-run: every step checks what is already in place and skips it.
    Run in Windows PowerShell as your normal user; it elevates itself for the
    few steps that need admin rights.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\setup-dev-machine.ps1
    powershell -ExecutionPolicy Bypass -File .\setup-dev-machine.ps1 -RepoDir D:\dev\ChapterPlayer -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$RepoDir = (Join-Path $HOME 'ClaudeProjects\ChapterPlayer'),
    [string]$RepoUrl = 'https://github.com/rameshki/ChapterPlayer.git',
    [string]$AvdName = 'ChapterPlayer_Pixel',
    [switch]$SkipEmulator,
    [switch]$SkipBuild
)

# 'Continue' (not 'Stop'): in Windows PowerShell 5.1, native tools that write to
# stderr (git, gh, gradle) raise terminating errors under 'Stop'. Native exit codes
# are checked explicitly, and critical cmdlets use -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar in PS 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:RebootNeeded = $false
$script:Notes = New-Object System.Collections.Generic.List[string]

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    [ok] $msg" -ForegroundColor Green }
function Write-Info([string]$msg) { Write-Host "    $msg" }
function Write-Warn2([string]$msg){ Write-Host "    [!] $msg" -ForegroundColor Yellow; $script:Notes.Add($msg) }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

function Add-UserPath([string]$dir) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $current) { $current = '' }
    $parts = $current.Split(';') | Where-Object { $_ -ne '' }
    if ($parts -notcontains $dir) {
        [Environment]::SetEnvironmentVariable('Path', (($parts + $dir) -join ';'), 'User')
        Write-Ok "Added to user PATH: $dir"
    }
    if (($env:Path.Split(';')) -notcontains $dir) { $env:Path = "$env:Path;$dir" }
}

function Set-UserEnv([string]$name, [string]$value) {
    if ([Environment]::GetEnvironmentVariable($name, 'User') -ne $value) {
        [Environment]::SetEnvironmentVariable($name, $value, 'User')
        Write-Ok "Set $name = $value"
    }
    Set-Item -Path "env:$name" -Value $value
}

# Runs a native tool and prints its stderr as plain text. Windows PowerShell 5.1
# otherwise renders every stderr line (progress, warnings) as a red NativeCommandError.
function Invoke-Native([scriptblock]$cmd) {
    & $cmd 2>&1 | ForEach-Object { Write-Host "    $_" }
}

function Test-Command([string]$name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Install-WingetPackage([string]$id, [string]$label) {
    & winget list --id $id -e --accept-source-agreements *> $null
    if ($LASTEXITCODE -eq 0) { Write-Ok "$label already installed"; return }
    Write-Info "Installing $label (winget: $id)..."
    & winget install --id $id -e --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) { throw "winget failed to install $label (exit $LASTEXITCODE)" }
    Write-Ok "$label installed"
}

# -----------------------------------------------------------------------------
# 1. Admin-only system settings (run in an elevated child process)
# -----------------------------------------------------------------------------
Write-Step 'System settings (long paths, emulator hypervisor)'

$adminWork = @'
$ErrorActionPreference = 'Stop'
$result = @()
$lp = 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem'
if ((Get-ItemProperty $lp -Name LongPathsEnabled -ErrorAction SilentlyContinue).LongPathsEnabled -ne 1) {
    Set-ItemProperty $lp -Name LongPathsEnabled -Value 1 -Type DWord
    $result += 'longpaths-set'
}
$f = Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform
if ($f.State -ne 'Enabled') {
    $r = Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
    $result += 'whpx-enabled'
    if ($r.RestartNeeded) { $result += 'reboot' }
}
$result -join ','
'@

$adminOut = $null
if (Test-Admin) {
    $adminOut = Invoke-Expression $adminWork
} else {
    Write-Info 'Requesting admin rights for system settings (a UAC prompt will appear)...'
    $tmp = Join-Path $env:TEMP 'chapterplayer-admin.ps1'
    $outFile = Join-Path $env:TEMP 'chapterplayer-admin.out'
    Set-Content -Path $tmp -Value ("& {`n" + $adminWork + "`n} | Set-Content -Path '$outFile'") -Encoding ASCII
    Remove-Item $outFile -ErrorAction SilentlyContinue
    $p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$tmp`""
    if ($p.ExitCode -ne 0) { Write-Warn2 'Admin step failed or was declined: enable long paths and Windows Hypervisor Platform manually.' }
    if (Test-Path $outFile) { $adminOut = Get-Content $outFile -Raw }
    Remove-Item $tmp, $outFile -ErrorAction SilentlyContinue
}
if ($adminOut -match 'longpaths-set') { Write-Ok 'Enabled Windows long paths' } else { Write-Ok 'Long paths already enabled' }
if ($adminOut -match 'whpx-enabled') { Write-Ok 'Enabled Windows Hypervisor Platform (emulator acceleration)' } else { Write-Ok 'Windows Hypervisor Platform already enabled' }
if ($adminOut -match 'reboot') { $script:RebootNeeded = $true }

# -----------------------------------------------------------------------------
# 2. Core tools via winget
# -----------------------------------------------------------------------------
Write-Step 'Core tools (Git, GitHub CLI, Android Studio)'
if (-not (Test-Command winget)) {
    throw 'winget not found. Install "App Installer" from the Microsoft Store, then re-run this script.'
}
Install-WingetPackage 'Git.Git' 'Git for Windows'
Install-WingetPackage 'GitHub.cli' 'GitHub CLI'
Install-WingetPackage 'Google.AndroidStudio' 'Android Studio'
Install-WingetPackage 'EclipseAdoptium.Temurin.17.JDK' 'JDK 17 (Temurin)'
Update-SessionPath

# -----------------------------------------------------------------------------
# 3. Git configuration
# -----------------------------------------------------------------------------
Write-Step 'Git configuration'
& git config --global core.longpaths true
& git config --global core.autocrlf true
& git config --global init.defaultBranch main
$gitName = (& git config --global user.name) 2>$null
$gitEmail = (& git config --global user.email) 2>$null
if (-not $gitName) {
    $gitName = Read-Host '    Git commit name (e.g. Robert Morris)'
    & git config --global user.name $gitName
}
if (-not $gitEmail) {
    $gitEmail = Read-Host '    Git commit email'
    & git config --global user.email $gitEmail
}
Write-Ok "Git user: $(& git config --global user.name) <$(& git config --global user.email)>"

# -----------------------------------------------------------------------------
# 4. Java (JDK 17) and Android SDK environment
# -----------------------------------------------------------------------------
# Not Android Studio's bundled JBR: newer Studio releases bundle Java 25, which
# the project's Gradle 8.8 cannot run on (max Java 22). JDK 17 matches CI.
Write-Step 'Java and Android SDK environment'
$jdk = Get-ChildItem (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk -or -not (Test-Path (Join-Path $jdk.FullName 'bin\java.exe'))) {
    throw 'JDK 17 not found under "C:\Program Files\Eclipse Adoptium". Re-run the script, or install Temurin 17 manually.'
}
Set-UserEnv 'JAVA_HOME' $jdk.FullName

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
New-Item -ItemType Directory -Force -Path $sdk | Out-Null
Set-UserEnv 'ANDROID_HOME' $sdk
Add-UserPath (Join-Path $sdk 'platform-tools')
Add-UserPath (Join-Path $sdk 'emulator')
Add-UserPath (Join-Path $sdk 'cmdline-tools\latest\bin')

# -----------------------------------------------------------------------------
# 5. Android SDK command-line tools + packages
# -----------------------------------------------------------------------------
Write-Step 'Android SDK packages'
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdmanager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'

if (-not (Test-Path $sdkmanager)) {
    Write-Info 'Downloading Android SDK command-line tools (latest stable)...'
    $feed = 'https://dl.google.com/android/repository/repository2-3.xml'
    $xml = [xml](Invoke-WebRequest $feed -UseBasicParsing -ErrorAction Stop).Content
    $pkg = $xml.DocumentElement.remotePackage |
        Where-Object { $_.path -eq 'cmdline-tools;latest' -and $_.channelRef.ref -eq 'channel-0' } |
        Select-Object -First 1
    $archive = $pkg.archives.archive | Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -First 1
    if (-not $archive) { throw 'Could not find the Windows command-line tools in the SDK feed.' }
    $zipUrl = 'https://dl.google.com/android/repository/' + $archive.complete.url
    $zip = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    $unzip = Join-Path $env:TEMP 'android-cmdline-tools'
    Invoke-WebRequest $zipUrl -OutFile $zip -UseBasicParsing -ErrorAction Stop
    Remove-Item $unzip -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip -DestinationPath $unzip -Force -ErrorAction Stop
    $dest = Join-Path $sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item (Join-Path $unzip 'cmdline-tools') $dest -ErrorAction Stop
    Remove-Item $zip, $unzip -Recurse -Force -ErrorAction SilentlyContinue
    Write-Ok "Command-line tools installed (rev $($pkg.revision.major).$($pkg.revision.minor))"
} else {
    Write-Ok 'Command-line tools already installed'
}

$abi = 'x86_64'
if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $abi = 'arm64-v8a' }
$systemImage = "system-images;android-35;google_apis_playstore;$abi"

$packages = @(
    'platform-tools',
    'emulator',
    'platforms;android-34',
    'platforms;android-35',
    'build-tools;34.0.0',
    'build-tools;35.0.0',
    'extras;google;auto'          # Android Auto Desktop Head Unit (DHU)
)
if (-not $SkipEmulator) { $packages += $systemImage }

Write-Info 'Accepting SDK licenses...'
$yes = ('y' + [Environment]::NewLine) * 30
$yes | & $sdkmanager --sdk_root=$sdk --licenses *> $null

Write-Info "Installing: $($packages -join ', ')"
Write-Info '(first run downloads a few GB; this can take a while)'
# A package file avoids passing ';' in arguments through sdkmanager.bat
$pkgFile = Join-Path $env:TEMP 'chapterplayer-sdk-packages.txt'
Set-Content -Path $pkgFile -Value $packages -Encoding ASCII
& $sdkmanager --sdk_root=$sdk "--package_file=$pkgFile"
Remove-Item $pkgFile -ErrorAction SilentlyContinue
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed (exit $LASTEXITCODE)" }
Write-Ok 'SDK packages installed'

# -----------------------------------------------------------------------------
# 6. Emulator (AVD)
# -----------------------------------------------------------------------------
if (-not $SkipEmulator) {
    Write-Step "Emulator: $AvdName"
    $existing = & (Join-Path $sdk 'emulator\emulator.exe') -list-avds 2>$null
    if ($existing -contains $AvdName) {
        Write-Ok "AVD $AvdName already exists"
    } else {
        'no' | & $avdmanager create avd -n $AvdName -k $systemImage -d pixel_8 --force *> $null
        if ($LASTEXITCODE -ne 0) {
            # Older avdmanager builds may not know pixel_8
            'no' | & $avdmanager create avd -n $AvdName -k $systemImage --force *> $null
        }
        if ($LASTEXITCODE -eq 0) { Write-Ok "Created AVD $AvdName ($systemImage)" }
        else { Write-Warn2 "Could not create the AVD; create one in Android Studio > Device Manager." }
    }
}

# -----------------------------------------------------------------------------
# 7. Claude Code
# -----------------------------------------------------------------------------
Write-Step 'Claude Code'
Add-UserPath (Join-Path $HOME '.local\bin')
if (Test-Command claude) {
    Write-Ok "Claude Code already installed ($(& claude --version 2>$null))"
} else {
    Write-Info 'Installing Claude Code (official native installer)...'
    Invoke-RestMethod https://claude.ai/install.ps1 | Invoke-Expression
    Update-SessionPath
    Add-UserPath (Join-Path $HOME '.local\bin')
    if (Test-Command claude) { Write-Ok 'Claude Code installed' }
    else { Write-Warn2 'Claude Code installer finished but "claude" is not on PATH yet; open a new terminal.' }
}
# Claude Code on Windows uses Git Bash for its shell tool
$gitBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
if (Test-Path $gitBash) { Set-UserEnv 'CLAUDE_CODE_GIT_BASH_PATH' $gitBash }

# -----------------------------------------------------------------------------
# 8. GitHub auth + clone
# -----------------------------------------------------------------------------
Write-Step 'GitHub'
& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Info 'Sign in to GitHub (a browser window will open)...'
    # Not piped: gh needs a real console for the interactive sign-in prompt
    & gh auth login --hostname github.com --git-protocol https --web
}
& gh auth setup-git
Write-Ok 'GitHub CLI signed in and set up as git credential helper'

if (Test-Path (Join-Path $RepoDir '.git')) {
    Write-Ok "Repo already cloned at $RepoDir"
    Invoke-Native { git -C $RepoDir pull --ff-only }
} else {
    New-Item -ItemType Directory -Force -Path (Split-Path $RepoDir) | Out-Null
    Invoke-Native { git clone $RepoUrl $RepoDir }
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
    Write-Ok "Cloned to $RepoDir"
}
$sdkEscaped = $sdk.Replace('\', '\\').Replace(':', '\:')
Set-Content -Path (Join-Path $RepoDir 'local.properties') -Value "sdk.dir=$sdkEscaped" -Encoding ASCII
Write-Ok 'Wrote local.properties'

# -----------------------------------------------------------------------------
# 9. Test build
# -----------------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Step 'Test build (debug APK)'
    Push-Location $RepoDir
    try {
        Invoke-Native { .\gradlew.bat :app:assembleDebug --console=plain }
        if ($LASTEXITCODE -eq 0) { Write-Ok 'Build succeeded: app\build\outputs\apk\debug\app-debug.apk' }
        else { Write-Warn2 'Test build failed; open the project in Android Studio to see the error.' }
    } finally { Pop-Location }
}

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------
Write-Step 'Done'
foreach ($n in $script:Notes) { Write-Host "    [!] $n" -ForegroundColor Yellow }
if ($script:RebootNeeded) {
    Write-Host "`n    REBOOT REQUIRED before the emulator will run (Windows Hypervisor Platform was enabled)." -ForegroundColor Yellow
}
Write-Host @"

    Next steps:
      1. Open a NEW terminal (so PATH and env vars apply).
      2. Open Android Studio once and finish its setup wizard; it will reuse this SDK.
         Then set Settings > Build, Execution, Deployment > Build Tools > Gradle >
         Gradle JDK to "JAVA_HOME" (JDK 17); the bundled Java 25 cannot run this project.
      3. Start the emulator:   emulator -avd $AvdName
      4. Start Claude Code:    cd "$RepoDir"; claude
         - Sign in when prompted.
         - Optional: run /remote-control to drive this session from another device.
      5. Android Auto testing: run the Desktop Head Unit from
         $sdk\extras\google\auto\desktop-head-unit.exe
"@
