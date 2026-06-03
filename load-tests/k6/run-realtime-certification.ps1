param(
    [string]$HostName = "localhost:8443",
    [string]$HttpScheme = "https",
    [string]$Usernames = $env:USERNAMES,
    [string]$UsernamesFile = $env:USERNAMES_FILE,
    [string]$Password = $env:PASSWORD,
    [string]$Passwords = $env:PASSWORDS,
    [string]$PasswordsFile = $env:PASSWORDS_FILE,
    [string]$AuthTokens = $env:AUTH_TOKENS,
    [string]$SessionIds = $env:SESSION_IDS,
    [string]$AuthTokensFile = $env:AUTH_TOKENS_FILE,
    [string]$SessionIdsFile = $env:SESSION_IDS_FILE,
    [string[]]$Scenarios = @("ws_500", "ws_1000", "ws_2000"),
    [int]$SessionDurationSeconds = 0,
    [switch]$InsecureTls
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Script = Join-Path $ScriptDir "ws_load_test.js"
$ReportDir = Join-Path (Split-Path -Parent $ScriptDir) "reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

function Get-ListCount([string]$InlineValue, [string]$FilePath, [string]$Name) {
    if ($FilePath) {
        if (-not (Test-Path -LiteralPath $FilePath)) {
            throw "$Name file not found: $FilePath"
        }
        return @((Get-Content -LiteralPath $FilePath -Raw).Split(",") | Where-Object { $_.Trim() }).Count
    }
    if ($InlineValue) {
        return @($InlineValue.Split(",") | Where-Object { $_.Trim() }).Count
    }
    throw "$Name is required. Provide inline value or file path."
}

function Get-OptionalListCount([string]$InlineValue, [string]$FilePath, [string]$Name) {
    if ($FilePath) {
        if (-not (Test-Path -LiteralPath $FilePath)) {
            throw "$Name file not found: $FilePath"
        }
        return @((Get-Content -LiteralPath $FilePath -Raw).Split(",") | Where-Object { $_.Trim() }).Count
    }
    if ($InlineValue) {
        return @($InlineValue.Split(",") | Where-Object { $_.Trim() }).Count
    }
    return 0
}

function Resolve-K6Path([string]$FilePath) {
    if (-not $FilePath) { return "" }
    return (Resolve-Path -LiteralPath $FilePath).Path.Replace("\", "/")
}

$usernameCount = Get-OptionalListCount $Usernames $UsernamesFile "USERNAMES"
$tokenCount = Get-OptionalListCount $AuthTokens $AuthTokensFile "AUTH_TOKENS"
$sessionCount = Get-OptionalListCount $SessionIds $SessionIdsFile "SESSION_IDS"
$usesLoginFlow = $usernameCount -gt 0
$usesTokenFlow = $tokenCount -gt 0 -and $sessionCount -gt 0

if (-not $usesLoginFlow -and -not $usesTokenFlow) {
    throw "Provide USERNAMES_FILE + PASSWORD for production tests, or AUTH_TOKENS_FILE + SESSION_IDS_FILE for short tests."
}
if ($usesLoginFlow -and -not $Password -and -not $Passwords -and -not $PasswordsFile) {
    throw "PASSWORD, PASSWORDS, or PASSWORDS_FILE is required when USERNAMES/USERNAMES_FILE is used."
}

$requiredCredentials = @{
    smoke = 1
    ws_500 = 500
    ws_1000 = 1000
    ws_2000 = 2000
    ping_storm = 1000
    soak = 1000
    connection_ramp = 10000
}

foreach ($scenario in $Scenarios) {
    if (-not $requiredCredentials.ContainsKey($scenario)) {
        throw "Unknown scenario: $scenario"
    }
    $required = $requiredCredentials[$scenario]
    if ($usesLoginFlow) {
        if ($usernameCount -lt $required) {
            throw "Scenario $scenario requires $required unique usernames; received $usernameCount."
        }
    } elseif ($tokenCount -lt $required -or $sessionCount -lt $required) {
        throw "Scenario $scenario requires $required unique identities; received $tokenCount tokens and $sessionCount session ids."
    }
}

if ($usesLoginFlow) {
    Write-Host "Unique realtime identities: usernames=$usernameCount login-per-VU=true"
} else {
    Write-Host "Unique realtime identities: tokens=$tokenCount sessions=$sessionCount"
}

$common = @(
    "run", $Script,
    "-e", "HOST=$HostName",
    "-e", "HTTP_SCHEME=$HttpScheme",
    "-e", "INSECURE_TLS=$($InsecureTls.IsPresent.ToString().ToLowerInvariant())"
)

if ($SessionDurationSeconds -gt 0) {
    $common += @("-e", "SESSION_DURATION_SECONDS=$SessionDurationSeconds")
}

if ($usesLoginFlow) {
    if ($UsernamesFile) {
        $common += @("-e", "USERNAMES_FILE=$(Resolve-K6Path $UsernamesFile)")
    } else {
        $common += @("-e", "USERNAMES=$Usernames")
    }

    if ($PasswordsFile) {
        $common += @("-e", "PASSWORDS_FILE=$(Resolve-K6Path $PasswordsFile)")
    } elseif ($Passwords) {
        $common += @("-e", "PASSWORDS=$Passwords")
    } else {
        $common += @("-e", "PASSWORD=$Password")
    }
} else {
    if ($AuthTokensFile) {
        $common += @("-e", "AUTH_TOKENS_FILE=$(Resolve-K6Path $AuthTokensFile)")
    } else {
        $common += @("-e", "AUTH_TOKENS=$AuthTokens")
    }

    if ($SessionIdsFile) {
        $common += @("-e", "SESSION_IDS_FILE=$(Resolve-K6Path $SessionIdsFile)")
    } else {
        $common += @("-e", "SESSION_IDS=$SessionIds")
    }
}

foreach ($scenario in $Scenarios) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $summary = Join-Path $ReportDir "k6-realtime-$scenario-$stamp.json"
    Write-Host "=== k6 realtime scenario: $scenario ==="
    & k6 @common -e "SCENARIO=$scenario" --summary-export $summary
    if ($LASTEXITCODE -ne 0) {
        throw "k6 scenario failed: $scenario"
    }
}
