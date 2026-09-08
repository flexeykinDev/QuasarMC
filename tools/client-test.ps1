<#
.SYNOPSIS
    Drives a real vanilla Minecraft client against Quasar and asserts on what the server saw.

.DESCRIPTION
    The bot swarm (dev.quasar.bench.BotSwarm) validates the engine but shares the server's own
    constants and has no client-side prediction, so it is blind by construction to every bug that
    lives in what the *client* believes. Those bugs have all been found by hand until now.

    This script automates that loop: it launches the real 1.21.4 client through PrismLauncher with
    an offline profile, waits for it to join, injects real keyboard and mouse input, then checks the
    server log for the actions the client should have produced and the client log for decode errors.

    No account is touched. Prism's --offline flag makes up a throwaway profile, which is all an
    offline-mode server needs, so the launcher's stored Microsoft session is never involved.

.EXAMPLE
    pwsh -File tools/client-test.ps1
    pwsh -File tools/client-test.ps1 -KeepOpen        # leave the client running to poke at it
    pwsh -File tools/client-test.ps1 -Scenario chat   # just prove input injection still works
#>
[CmdletBinding()]
param(
    # Prism instance ID, which is the folder name under <prism data>/instances.
    [string] $Instance = 'dev 1.21.4',

    [string] $PrismExe = 'D:\Apps\PrismLauncher\prismlauncher.exe',

    [string] $PrismData = "$env:APPDATA\PrismLauncher",

    # Offline profile name. Also the name asserted against in the server log.
    [string] $ProfileName = 'QuasarBot',

    [string] $ServerJar,

    # Working directory for the server under test. Wiped unless -KeepWorld.
    [string] $RunDir,

    [int] $Port = 25565,

    [ValidateSet('chat', 'full', 'light')]
    [string] $Scenario = 'full',

    # Attach to a server that is already running instead of starting one.
    [switch] $NoServer,

    # Keep the world from the previous run rather than generating a fresh one.
    [switch] $KeepWorld,

    # Leave the client and server running when the scenario finishes.
    [switch] $KeepOpen
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not populated inside param() defaults on Windows PowerShell 5.1, so these are
# resolved here instead. Deriving them from $PSCommandPath keeps the script runnable from any cwd.
$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
if (-not $ServerJar) { $ServerJar = Join-Path $root 'build\libs\quasar-0.1.0-all.jar' }
if (-not $RunDir) { $RunDir = Join-Path $root 'run-client' }

# ---------------------------------------------------------------------------------------------
# Win32 input injection.
#
# SendKeys cannot hold a key down, which rules out walking, and cannot move the mouse at all, which
# rules out looking. SendInput with scan codes is what games actually read: GLFW takes keyboard from
# the window message queue and mouse motion from raw input, and SendInput feeds both.
# ---------------------------------------------------------------------------------------------
Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class Native {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] i, int size);

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Explicit)]
    public struct INPUT {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public MOUSEINPUT mi;
        [FieldOffset(8)] public KEYBDINPUT ki;
    }

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint KEYEVENTF_SCANCODE = 0x0008, KEYEVENTF_KEYUP = 0x0002;

    public static void Key(ushort scan, bool down) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_KEYBOARD;
        i[0].ki.wScan = scan;
        i[0].ki.dwFlags = KEYEVENTF_SCANCODE | (down ? 0u : KEYEVENTF_KEYUP);
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void Mouse(uint flags, int dx, int dy) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_MOUSE;
        i[0].mi.dx = dx; i[0].mi.dy = dy; i[0].mi.dwFlags = flags;
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }
}
'@

# Scan codes, not virtual keys: the game reads physical keys, so these stay correct whatever the
# active keyboard layout is. That matters here -- this machine switches to a Russian layout, and a
# virtual-key "T" would type the wrong character into chat.
$SCAN = @{
    W = 0x11; A = 0x1E; S = 0x1F; D = 0x20; Q = 0x10; E = 0x12; T = 0x14
    SPACE = 0x39; ENTER = 0x1C; ESC = 0x01; F2 = 0x3C; SHIFT = 0x2A
    D1 = 0x02; D2 = 0x03; D3 = 0x04; D9 = 0x0A
}

$MOUSE = @{
    LEFTDOWN = 0x0002; LEFTUP = 0x0004
    RIGHTDOWN = 0x0008; RIGHTUP = 0x0010
    MIDDLEDOWN = 0x0020; MIDDLEUP = 0x0040
    MOVE = 0x0001
}

function Stop-Quasar {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*quasar-*-all.jar*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Focus-Client {
    $mc = Get-Process javaw -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $mc) { throw 'Minecraft client is not running.' }
    [Native]::ShowWindow($mc.MainWindowHandle, 9) | Out-Null
    [Native]::SetForegroundWindow($mc.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 700
    if ([Native]::GetForegroundWindow() -ne $mc.MainWindowHandle) {
        throw 'Could not bring the client to the foreground; input would go to the wrong window.'
    }
    $mc
}

function Tap([int] $scan, [int] $ms = 60) {
    [Native]::Key($scan, $true); Start-Sleep -Milliseconds $ms; [Native]::Key($scan, $false)
    Start-Sleep -Milliseconds 120
}

function Hold([int] $scan, [int] $ms) {
    [Native]::Key($scan, $true); Start-Sleep -Milliseconds $ms; [Native]::Key($scan, $false)
    Start-Sleep -Milliseconds 150
}

function Click([string] $button, [int] $ms = 120) {
    [Native]::Mouse($MOUSE["${button}DOWN"], 0, 0)
    Start-Sleep -Milliseconds $ms
    [Native]::Mouse($MOUSE["${button}UP"], 0, 0)
    Start-Sleep -Milliseconds 250
}

function Look([int] $dx, [int] $dy) {
    # The cursor is captured in-world, so motion is relative. Split into small steps: one large
    # jump can be swallowed or clamped, and a smooth sweep is what a person's input looks like.
    $steps = 10
    for ($i = 0; $i -lt $steps; $i++) {
        [Native]::Mouse($MOUSE.MOVE, [int]($dx / $steps), [int]($dy / $steps))
        Start-Sleep -Milliseconds 16
    }
    Start-Sleep -Milliseconds 200
}

function Say([string] $text) {
    Tap $SCAN.T 80
    Start-Sleep -Milliseconds 400
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.SendKeys]::SendWait($text)
    Start-Sleep -Milliseconds 300
    Tap $SCAN.ENTER 80
    Start-Sleep -Milliseconds 400
}

# ---------------------------------------------------------------------------------------------
# Server under test
# ---------------------------------------------------------------------------------------------
# A per-run log name. Reusing one file means a server that outlived a previous run keeps the
# handle open, the new server's output goes nowhere, and every assertion below is then read from
# the *previous* run's log -- which passes or fails for reasons that have nothing to do with this
# run. A fresh name makes that impossible.
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$serverLog = Join-Path $RunDir "server-$stamp.log"
$serverProc = $null

if (-not $NoServer) {
    if (-not (Test-Path $ServerJar)) { throw "Server jar not found: $ServerJar. Run: ./gradlew fatJar" }

    # Never rebuild the jar while a server runs from it -- the JVM loads classes lazily and later
    # loads fail with NoClassDefFoundError. Kill first, always.
    Stop-Quasar

    # Wait for the port to actually come free. Stop-Process returns before the socket is released,
    # and a server that loses the bind race logs the failure and exits, which reads downstream as
    # 'did not become ready' with no clue why.
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 500
    }

    if (-not $KeepWorld -and (Test-Path $RunDir)) {
        Remove-Item -Recurse -Force (Join-Path $RunDir 'world') -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

    # A superflat world, so the scenario is deterministic. On noise terrain the spawn is wherever
    # the generator puts it, and the first run walked straight into an ocean -- break and place then
    # act on water and the run fails for reasons that have nothing to do with the code under test.
    if (-not $KeepWorld) {
        # Interpolated, not concatenated: @('a', 'b' + $Port) parses as a three-element array,
        # because the comma builds the array first and + then appends to it. That wrote an empty
        # 'server.port=' line followed by a bare '25565', which the server rejected.
        @("world.generator=flat", "server.port=$Port") |
            Set-Content -Path (Join-Path $RunDir 'quasar.properties') -Encoding ascii
    }

    # The generated reports are gitignored, so copy whatever the repo root has. Without them the
    # server falls back to its built-in block table and a hotbar-sized creative inventory.
    foreach ($f in 'blocks.json', 'registries.json') {
        $src = Join-Path $root $f
        if ((Test-Path $src) -and -not (Test-Path (Join-Path $RunDir $f))) {
            Copy-Item $src $RunDir
        }
    }

    Write-Host 'Starting Quasar...' -ForegroundColor Cyan
    $java = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\java.exe" } else { 'java' }
    Start-Process -FilePath $java `
        -ArgumentList '-Dquasar.noColor=true', '-jar', $ServerJar, '--debug' `
        -WorkingDirectory $RunDir -RedirectStandardOutput $serverLog -NoNewWindow

    # Matched on an ASCII-only line. The banner's 'Ready --' uses an em dash, and Select-String
    # reading a UTF-8 log under the console's default codepage does not reliably match it.
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        if ((Test-Path $serverLog) -and (Select-String -Path $serverLog -Pattern 'Listening on' -Quiet)) {
            $ready = $true; break
        }
    }
    if (-not $ready) { throw "Server did not become ready. See $serverLog" }
    Write-Host '  ready' -ForegroundColor Green
}

# ---------------------------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------------------------
$clientLog = Join-Path $PrismData "instances\$Instance\minecraft\logs\latest.log"
$shotDir = Join-Path $PrismData "instances\$Instance\minecraft\screenshots"
$debugDir = Join-Path $PrismData "instances\$Instance\minecraft\debug"

# Remember what already exists so only this run's artefacts are reported.
$shotsBefore = @(Get-ChildItem $shotDir -Filter *.png -ErrorAction SilentlyContinue | Select-Object -Expand Name)
$reportsBefore = @(Get-ChildItem $debugDir -Filter *.txt -ErrorAction SilentlyContinue | Select-Object -Expand Name)

Write-Host "Launching client (instance '$Instance', offline profile '$ProfileName')..." -ForegroundColor Cyan

# Pass the instance ID as one argument. Splitting it on the space is a silent failure: Prism logs
# 'Launch command requires an valid instance ID' to its own log and exits 0, so the only symptom is
# a client that never appears.
# Start-Process, not the call operator: when Prism is not already running this call owns the
# launcher process and blocks until the user quits it, which hangs the whole run.
# The instance ID is quoted because Start-Process joins ArgumentList on spaces without quoting,
# and 'dev 1.21.4' would arrive as two arguments. Prism then logs 'Launch command requires an
# valid instance ID' to its own log and exits 0 -- a silent failure whose only symptom is a client
# that never appears.
Start-Process -FilePath $PrismExe -ArgumentList @(
    '--launch', ('"' + $Instance + '"'),
    '--server', "127.0.0.1:$Port",
    '--offline', $ProfileName) | Out-Null

$joined = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    if (Select-String -Path $serverLog -Pattern "$ProfileName joined" -Quiet -ErrorAction SilentlyContinue) {
        $joined = $true
        Write-Host "  joined after ~$($i * 2)s" -ForegroundColor Green
        break
    }
}
if (-not $joined) {
    Write-Host '  client never joined' -ForegroundColor Red
    Write-Host '  check the Prism log:' (Join-Path $PrismData 'logs\PrismLauncher-0.log')
    exit 1
}

Start-Sleep -Seconds 3   # let terrain finish streaming before driving
$mc = Focus-Client
Write-Host "  window: $($mc.MainWindowTitle)" -ForegroundColor Green

# ---------------------------------------------------------------------------------------------
# Scenario
# ---------------------------------------------------------------------------------------------
Write-Host "Running scenario '$Scenario'..." -ForegroundColor Cyan

Say 'automation: begin'

if ($Scenario -eq 'full') {
    Write-Host '  walking'
    Hold $SCAN.W 1200
    Hold $SCAN.D 500
    Tap $SCAN.SPACE

    Write-Host '  looking down at the ground'
    Look 0 320

    # A short click, not a hold. Creative breaks instantly and keeps breaking while the button is
    # down, so a 600ms hold dug a three-block hole; the next right-click then had no reachable face
    # to place against and the run failed for a reason unrelated to placement.
    Write-Host '  breaking'
    Click 'LEFT' 90

    Write-Host '  placing'
    Click 'RIGHT'

    Write-Host '  pick block (middle mouse)'
    Click 'MIDDLE'

    Write-Host '  dropping held stack'
    Tap $SCAN.Q

    Write-Host '  opening and closing inventory'
    Tap $SCAN.E
    Start-Sleep -Milliseconds 800
    Tap $SCAN.ESC

    Write-Host '  screenshot (F2)'
    Tap $SCAN.F2
    Start-Sleep -Milliseconds 800
}

if ($Scenario -eq 'light') {
    # Proves the light engine visually. A flat world in daylight looks identical whether light is
    # propagated or faked full-bright, so the only convincing evidence is a hole deep enough to be
    # dark and a torch that lights it back up.
    Write-Host '  digging down'
    Look 0 400
    Click 'LEFT' 1400
    Start-Sleep -Milliseconds 1200

    Write-Host '  screenshot in the hole (expect shadow)'
    Tap $SCAN.F2
    Start-Sleep -Milliseconds 900

    Write-Host '  selecting the torch (hotbar slot 9)'
    Tap $SCAN.D9
    Start-Sleep -Milliseconds 400

    Write-Host '  placing the torch'
    Look 0 -150
    Click 'RIGHT'
    Start-Sleep -Milliseconds 900

    Write-Host '  screenshot with torch (expect light)'
    Tap $SCAN.F2
    Start-Sleep -Milliseconds 900
}

Say 'automation: end'
Start-Sleep -Seconds 2

# ---------------------------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------------------------
Write-Host ''
Write-Host '=== what the server saw ===' -ForegroundColor Cyan

$checks = [ordered] @{
    'joined'       = "$ProfileName joined"
    'chat'         = 'automation: begin'
    'moved'        = 'Unhandled play packet'   # presence only; movement itself is silent
}
if ($Scenario -eq 'full') {
    $checks['broke a block'] = "$ProfileName broke block"
    $checks['placed a block'] = "$ProfileName placed block"
    # Anchored on the block name that pickBlock logs. Matching '$ProfileName picked' alone also
    # matches 'picked up 1 x item 1' from an item entity, which passed this check without a single
    # middle-click ever being handled.
    $checks['picked a block'] = "$ProfileName picked minecraft:"
    $checks['dropped an item'] = "$ProfileName dropped"
}

$failed = 0
foreach ($name in $checks.Keys) {
    $hit = Select-String -Path $serverLog -Pattern $checks[$name] -Quiet -ErrorAction SilentlyContinue
    if ($hit) {
        Write-Host ("  [ok]   {0}" -f $name) -ForegroundColor Green
    } else {
        Write-Host ("  [MISS] {0}" -f $name) -ForegroundColor Yellow
        $failed++
    }
}

Write-Host ''
Write-Host '=== server errors ===' -ForegroundColor Cyan
$errors = Select-String -Path $serverLog -Pattern 'ERROR|Exception|WARN' -ErrorAction SilentlyContinue
if ($errors) { $errors | ForEach-Object { Write-Host "  $($_.Line)" -ForegroundColor Yellow } }
else { Write-Host '  none' -ForegroundColor Green }

Write-Host ''
Write-Host '=== client errors ===' -ForegroundColor Cyan
# This is the half the bot swarm can never see: a client that decodes a packet wrongly says so here
# and nowhere else. The set_default_spawn_position ID collision surfaced exactly like this.
# An offline profile has no Mojang session, so authlib fails to fetch user properties and the
# profile key pair with 401s on every run. That is expected here and would drown out a real decode
# error, so it is filtered rather than reported.
$benign = 'Failed to fetch user properties|Failed to retrieve profile key pair|InvalidCredentialsException|MinecraftClientHttpException|authlib'
$clientErrors = Select-String -Path $clientLog -Pattern 'ERROR|Exception|Failed|Unbound|extra bytes|Invalid' -ErrorAction SilentlyContinue |
    Where-Object { $_.Line -notmatch $benign }
if ($clientErrors) { $clientErrors | Select-Object -Last 15 | ForEach-Object { Write-Host "  $($_.Line)" -ForegroundColor Yellow } }
else { Write-Host '  none' -ForegroundColor Green }

$newReports = @(Get-ChildItem $debugDir -Filter *.txt -ErrorAction SilentlyContinue |
    Where-Object { $reportsBefore -notcontains $_.Name })
if ($newReports) {
    Write-Host ''
    Write-Host '=== NEW client disconnect reports ===' -ForegroundColor Red
    $newReports | ForEach-Object { Write-Host "  $($_.FullName)" -ForegroundColor Red }
}

$newShots = @(Get-ChildItem $shotDir -Filter *.png -ErrorAction SilentlyContinue |
    Where-Object { $shotsBefore -notcontains $_.Name })
if ($newShots) {
    Write-Host ''
    Write-Host '=== screenshots ===' -ForegroundColor Cyan
    $newShots | ForEach-Object { Write-Host "  $($_.FullName)" }
}

# ---------------------------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------------------------
if (-not $KeepOpen) {
    Write-Host ''
    Write-Host 'Shutting down...' -ForegroundColor Cyan
    Get-Process javaw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Stop-Quasar
}

Write-Host ''
if ($failed -eq 0) { Write-Host 'PASS' -ForegroundColor Green; exit 0 }
Write-Host "$failed check(s) missed" -ForegroundColor Yellow
exit 1
