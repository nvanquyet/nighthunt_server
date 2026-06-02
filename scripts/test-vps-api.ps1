[CmdletBinding()]
param(
    [string]$ApiBase = "https://vawnwuyest.me/api",
    [string]$Username = "",
    [string]$Password = "",
    [string]$AccessToken = "",
    [string]$SessionId = ""
)

$ErrorActionPreference = "Stop"
$ApiBase = $ApiBase.TrimEnd("/")
$script:Passed = 0
$script:Failed = 0

Add-Type -AssemblyName System.Net.Http

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)

function Pass([string]$Message) {
    Write-Host "PASS $Message" -ForegroundColor Green
    $script:Passed++
}

function Fail([string]$Message) {
    Write-Host "FAIL $Message" -ForegroundColor Red
    $script:Failed++
}

function Info([string]$Message) {
    Write-Host "INFO $Message" -ForegroundColor Cyan
}

function Invoke-Http(
    [string]$Method,
    [string]$Url,
    [hashtable]$Headers = @{},
    [string]$JsonBody = ""
) {
    $request = New-Object System.Net.Http.HttpRequestMessage(
        (New-Object System.Net.Http.HttpMethod($Method)),
        $Url
    )
    try {
        foreach ($entry in $Headers.GetEnumerator()) {
            [void]$request.Headers.TryAddWithoutValidation($entry.Key, [string]$entry.Value)
        }
        if ($JsonBody) {
            $request.Content = New-Object System.Net.Http.StringContent(
                $JsonBody,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                Location = if ($response.Headers.Location) { $response.Headers.Location.ToString() } else { "" }
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

function Assert-Status([string]$Label, $Response, [int[]]$AllowedStatusCodes) {
    if ($AllowedStatusCodes -contains $Response.StatusCode) {
        Pass "$Label -> HTTP $($Response.StatusCode)"
    } else {
        Fail "$Label -> expected HTTP $($AllowedStatusCodes -join "/"), got $($Response.StatusCode)"
    }
}

function Connect-WebSocket([string]$Url, [bool]$ExpectSuccess) {
    $socket = New-Object System.Net.WebSockets.ClientWebSocket
    $timeout = New-Object System.Threading.CancellationTokenSource
    $timeout.CancelAfter([TimeSpan]::FromSeconds(10))
    try {
        $socket.ConnectAsync([Uri]$Url, $timeout.Token).GetAwaiter().GetResult()
        if (-not $ExpectSuccess) {
            Fail "Consumed realtime ticket was accepted a second time"
            return
        }

        Pass "WSS ticket handshake accepted"
        $buffer = New-Object byte[] 4096
        $segment = New-Object System.ArraySegment[byte] -ArgumentList @(,$buffer)
        $result = $socket.ReceiveAsync($segment, $timeout.Token).GetAwaiter().GetResult()
        $message = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
        if ($message -match '"type"\s*:\s*"connected"') {
            Pass "WSS connected event received"
        } else {
            Fail "Expected WSS connected event, received: $message"
        }

        $closeTimeout = New-Object System.Threading.CancellationTokenSource
        $closeTimeout.CancelAfter([TimeSpan]::FromSeconds(5))
        try {
            $socket.CloseAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                "local-smoke-complete",
                $closeTimeout.Token
            ).GetAwaiter().GetResult()
        } catch {
            Info "WSS close acknowledgement was not returned before timeout"
        } finally {
            $closeTimeout.Dispose()
        }
    } catch {
        if ($ExpectSuccess) {
            Fail "WSS ticket handshake failed: $($_.Exception.Message)"
        } else {
            Pass "Consumed realtime ticket replay rejected"
        }
    } finally {
        $timeout.Dispose()
        $socket.Dispose()
    }
}

Write-Host ""
Write-Host "NightHunt VPS API and Netty gateway smoke test" -ForegroundColor White
Info "API base: $ApiBase"

try {
    $apiUri = [Uri]$ApiBase
    $origin = "$($apiUri.Scheme)://$($apiUri.Authority)"
    if ($apiUri.Scheme -eq "https") {
        $httpOrigin = "http://$($apiUri.Authority)"
        $redirect = Invoke-Http "GET" "$httpOrigin/"
        Assert-Status "HTTP to HTTPS redirect" $redirect @(301, 302, 307, 308)
        if ($redirect.Location -like "https://*") {
            Pass "HTTP redirect location uses HTTPS"
        } else {
            Fail "HTTP redirect location is not HTTPS: $($redirect.Location)"
        }
    }

    $health = Invoke-Http "GET" "$ApiBase/actuator/health"
    Assert-Status "Backend health" $health @(200)
    if ($health.Body -match '"status"\s*:\s*"UP"') {
        Pass "Backend health payload reports UP"
    } else {
        Fail "Backend health payload does not report UP"
    }

    $gameModes = Invoke-Http "GET" "$ApiBase/game-modes"
    Assert-Status "Public game modes" $gameModes @(200)
    if ($gameModes.StatusCode -eq 200) {
        $gameModesJson = $gameModes.Body | ConvertFrom-Json
        $fourVFour = $gameModesJson.data | Where-Object { $_.modeKey -eq "4v4" } | Select-Object -First 1
        if ($fourVFour -and $fourVFour.modeStatus -eq "AVAILABLE" -and $fourVFour.matchmakingEnabled -and $fourVFour.allowFill) {
            Pass "4v4 ranked Fill Party config is enabled"
        } else {
            Fail "4v4 ranked Fill Party config is not enabled"
        }
    }

    $maps = Invoke-Http "GET" "$ApiBase/maps/available"
    Assert-Status "Public maps" $maps @(200)
    if ($maps.StatusCode -eq 200) {
        $mapsJson = $maps.Body | ConvertFrom-Json
        $map01 = $mapsJson.data | Where-Object { $_.mapId -eq "map_01" } | Select-Object -First 1
        if ($map01 -and $map01.supportedPlayerCounts -contains 8) {
            Pass "map_01 supports 8-player 4v4 matches"
        } else {
            Fail "map_01 does not advertise 8-player 4v4 support"
        }
    }
    Assert-Status "Ticket endpoint rejects missing auth" (Invoke-Http "POST" "$ApiBase/realtime/tickets") @(401, 403)

    $missingTicket = Invoke-Http "GET" "$ApiBase/ws/game"
    Assert-Status "Netty gateway rejects missing ticket" $missingTicket @(401)
    if ($missingTicket.Body.Trim() -eq "Unauthorized") {
        Pass "WebSocket route returns Netty gateway unauthorized payload"
    } else {
        Fail "WebSocket route payload does not match Netty gateway contract"
    }

    if (($Username -and -not $Password) -or ($Password -and -not $Username)) {
        Fail "Provide both Username and Password, or neither"
    }
    if (($AccessToken -and -not $SessionId) -or ($SessionId -and -not $AccessToken)) {
        Fail "Provide both AccessToken and SessionId, or neither"
    }

    if (-not $AccessToken -and $Username -and $Password) {
        $loginBody = @{ identifier = $Username; password = $Password } | ConvertTo-Json -Compress
        $login = Invoke-Http "POST" "$ApiBase/auth/login" @{} $loginBody
        Assert-Status "Login" $login @(200)
        if ($login.StatusCode -eq 200) {
            $loginJson = $login.Body | ConvertFrom-Json
            $AccessToken = [string]$loginJson.data.accessToken
            $SessionId = [string]$loginJson.data.sessionId
        }
    }

    if ($AccessToken -and $SessionId) {
        $authHeaders = @{
            Authorization = "Bearer $AccessToken"
            "X-Session-ID" = $SessionId
        }
        $ticket = Invoke-Http "POST" "$ApiBase/realtime/tickets" $authHeaders
        Assert-Status "Realtime ticket issue" $ticket @(200)
        if ($ticket.StatusCode -eq 200) {
            $ticketJson = $ticket.Body | ConvertFrom-Json
            $ticketValue = [string]$ticketJson.data.ticket
            $wsPath = [string]$ticketJson.data.wsPath
            $wsOrigin = $origin -replace "^https://", "wss://" -replace "^http://", "ws://"
            $wsUrl = "$wsOrigin$wsPath?ticket=$([Uri]::EscapeDataString($ticketValue))"
            Connect-WebSocket $wsUrl $true
            Connect-WebSocket $wsUrl $false
        }
    } else {
        Info "Authenticated ticket + WSS + replay checks skipped. Pass Username/Password or AccessToken/SessionId to enable them."
    }
} catch {
    Fail "Unhandled probe error: $($_.Exception.Message)"
} finally {
    $client.Dispose()
    $handler.Dispose()
}

Write-Host ""
Write-Host "Passed: $script:Passed  Failed: $script:Failed" -ForegroundColor $(if ($script:Failed -eq 0) { "Green" } else { "Red" })
if ($script:Failed -gt 0) { exit 1 }
