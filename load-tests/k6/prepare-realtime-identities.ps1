[CmdletBinding()]
param(
    [string]$ApiBase = "https://vawnwuyest.me/api",
    [int]$Count = 2000,
    [string]$UsernamePrefix = "nh_ws_",
    [string]$Password = "StressTest@123",
    [int]$Parallelism = 20,
    [string]$OutputDir = "",
    [switch]$SkipRegister
)

$ErrorActionPreference = "Stop"
$ApiBase = $ApiBase.TrimEnd("/")
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $OutputDir) {
    $OutputDir = Join-Path (Split-Path -Parent $ScriptDir) "generated"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$tokensFile = Join-Path $OutputDir "auth-tokens-$Count.txt"
$sessionsFile = Join-Path $OutputDir "session-ids-$Count.txt"
$usernamesFile = Join-Path $OutputDir "usernames-$Count.txt"
$usersFile = Join-Path $OutputDir "users-$Count.csv"
$errorsFile = Join-Path $OutputDir "identity-errors-$Count.log"

Remove-Item -LiteralPath $tokensFile, $sessionsFile, $usernamesFile, $usersFile, $errorsFile -Force -ErrorAction SilentlyContinue

Add-Type -AssemblyName System.Net.Http

function New-HttpClient {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    return [pscustomobject]@{ Client = $client; Handler = $handler }
}

function Invoke-JsonRequest($Http, [string]$Method, [string]$Url, [object]$Body) {
    $request = New-Object System.Net.Http.HttpRequestMessage(
        (New-Object System.Net.Http.HttpMethod($Method)),
        $Url
    )
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Compress
            $request.Content = New-Object System.Net.Http.StringContent(
                $json,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }
        $response = $Http.Client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $payload = $null
            if ($text) {
                try { $payload = $text | ConvertFrom-Json } catch { $payload = $text }
            }
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Body = $payload
                Raw = $text
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

$worker = {
    param($Index, $ApiBase, $UsernamePrefix, $Password, $SkipRegister)

    Add-Type -AssemblyName System.Net.Http

    function New-HttpClient {
        $handler = New-Object System.Net.Http.HttpClientHandler
        $handler.AllowAutoRedirect = $false
        $client = New-Object System.Net.Http.HttpClient($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(20)
        return [pscustomobject]@{ Client = $client; Handler = $handler }
    }

    function Invoke-JsonRequest($Http, [string]$Method, [string]$Url, [object]$Body) {
        $request = New-Object System.Net.Http.HttpRequestMessage(
            (New-Object System.Net.Http.HttpMethod($Method)),
            $Url
        )
        try {
            if ($null -ne $Body) {
                $json = $Body | ConvertTo-Json -Compress
                $request.Content = New-Object System.Net.Http.StringContent(
                    $json,
                    [System.Text.Encoding]::UTF8,
                    "application/json"
                )
            }
            $response = $Http.Client.SendAsync($request).GetAwaiter().GetResult()
            try {
                $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $payload = $null
                if ($text) {
                    try { $payload = $text | ConvertFrom-Json } catch { $payload = $text }
                }
                return [pscustomobject]@{
                    StatusCode = [int]$response.StatusCode
                    Body = $payload
                    Raw = $text
                }
            } finally {
                $response.Dispose()
            }
        } finally {
            $request.Dispose()
        }
    }

    $username = "{0}{1}" -f $UsernamePrefix, $Index
    $email = "$username@stress.local"
    $http = New-HttpClient
    try {
        if (-not $SkipRegister) {
            $registerBody = @{
                username = $username
                email = $email
                password = $Password
                confirmPassword = $Password
            }
            $register = Invoke-JsonRequest $http "POST" "$ApiBase/auth/register" $registerBody
            if ($register.StatusCode -notin @(200, 409)) {
                return [pscustomobject]@{ Error = "register $username http=$($register.StatusCode) body=$($register.Raw)" }
            }
        }

        $loginBody = @{ identifier = $username; password = $Password }
        $login = Invoke-JsonRequest $http "POST" "$ApiBase/auth/login" $loginBody
        if ($login.StatusCode -ne 200 -or $login.Body.success -ne $true) {
            return [pscustomobject]@{ Error = "login $username http=$($login.StatusCode) body=$($login.Raw)" }
        }

        $accessToken = [string]$login.Body.data.accessToken
        $sessionId = [string]$login.Body.data.sessionId
        if (-not $accessToken -or -not $sessionId) {
            return [pscustomobject]@{ Error = "login $username missing token/session" }
        }

        return [pscustomobject]@{
            Username = $username
            Email = $email
            Token = $accessToken
            SessionId = $sessionId
            Error = ""
        }
    } catch {
        return [pscustomobject]@{ Error = "$username exception=$($_.Exception.Message)" }
    } finally {
        $http.Client.Dispose()
        $http.Handler.Dispose()
    }
}

Write-Host "Generating realtime identities: count=$Count api=$ApiBase prefix=$UsernamePrefix parallelism=$Parallelism"
$jobs = New-Object System.Collections.Generic.List[object]
$results = New-Object System.Collections.Generic.List[object]
for ($i = 1; $i -le $Count; $i++) {
    while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $Parallelism) {
        Start-Sleep -Milliseconds 200
        $done = @($jobs | Where-Object { $_.State -ne "Running" })
        foreach ($job in $done) {
            foreach ($result in @(Receive-Job $job)) {
                $results.Add($result) | Out-Null
            }
            Remove-Job $job
            [void]$jobs.Remove($job)
        }
    }
    $jobs.Add((Start-Job -ScriptBlock $worker -ArgumentList $i, $ApiBase, $UsernamePrefix, $Password, $SkipRegister.IsPresent)) | Out-Null
}

while ($jobs.Count -gt 0) {
    Start-Sleep -Milliseconds 300
    $done = @($jobs | Where-Object { $_.State -ne "Running" })
    foreach ($job in $done) {
        foreach ($result in @(Receive-Job $job)) {
            $results.Add($result) | Out-Null
        }
        Remove-Job $job
        [void]$jobs.Remove($job)
    }
}

$successes = @($results | Where-Object { -not $_.Error })
$errors = @($results | Where-Object { $_.Error } | ForEach-Object { $_.Error })

[string]::Join(",", [string[]]@($successes | ForEach-Object { $_.Token })) | Set-Content -LiteralPath $tokensFile -NoNewline -Encoding ASCII
[string]::Join(",", [string[]]@($successes | ForEach-Object { $_.SessionId })) | Set-Content -LiteralPath $sessionsFile -NoNewline -Encoding ASCII
[string]::Join(",", [string[]]@($successes | ForEach-Object { $_.Username })) | Set-Content -LiteralPath $usernamesFile -NoNewline -Encoding ASCII
@("username,email") + [string[]]@($successes | ForEach-Object { "$($_.Username),$($_.Email)" }) | Set-Content -LiteralPath $usersFile -Encoding ASCII
if ($errors.Count -gt 0) {
    [string[]]$errors | Set-Content -LiteralPath $errorsFile -Encoding UTF8
}

$tokenCount = $successes.Count
$sessionCount = $successes.Count
Write-Host "Generated tokens=$tokenCount sessions=$sessionCount"
Write-Host "Tokens file:   $tokensFile"
Write-Host "Sessions file: $sessionsFile"
Write-Host "Usernames file:$usernamesFile"
Write-Host "Users file:    $usersFile"
if ($errors.Count -gt 0) {
    Write-Host "Errors file:   $errorsFile"
    throw "Identity generation had $($errors.Count) errors. Inspect $errorsFile"
}
if ($tokenCount -lt $Count -or $sessionCount -lt $Count) {
    throw "Expected $Count identities, got tokens=$tokenCount sessions=$sessionCount"
}
