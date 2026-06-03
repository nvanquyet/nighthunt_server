[CmdletBinding()]
param(
    [string]$ReportDir = "",
    [string]$Pattern = "k6-realtime-*.json"
)

$ErrorActionPreference = "Stop"
if (-not $ReportDir) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $ReportDir = Join-Path (Split-Path -Parent $ScriptDir) "reports"
}

function MetricValue($Metrics, [string]$Name, [string]$ValueName) {
    if (-not $Metrics.$Name) { return $null }
    if ($Metrics.$Name.values) {
        return $Metrics.$Name.values.$ValueName
    }
    return $Metrics.$Name.$ValueName
}

function RateValue($Metrics, [string]$Name) {
    $value = MetricValue $Metrics $Name "rate"
    if ($null -ne $value) { return $value }
    return MetricValue $Metrics $Name "value"
}

$files = Get-ChildItem -LiteralPath $ReportDir -Filter $Pattern -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime

if (-not $files) {
    throw "No k6 reports found in $ReportDir matching $Pattern"
}

$rows = foreach ($file in $files) {
    $json = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    $metrics = $json.metrics
    [pscustomobject]@{
        File = $file.Name
        ConnectSuccessRate = RateValue $metrics "nighthunt_ws_connect_success_rate"
        PongP95Ms = MetricValue $metrics "nighthunt_ws_pong_latency_ms" "p(95)"
        PongP99Ms = MetricValue $metrics "nighthunt_ws_pong_latency_ms" "p(99)"
        TicketErrors = MetricValue $metrics "nighthunt_ws_ticket_errors" "count"
        LoginErrors = MetricValue $metrics "nighthunt_ws_login_errors" "count"
        ConnectErrors = MetricValue $metrics "nighthunt_ws_connect_errors" "count"
        RuntimeErrors = MetricValue $metrics "nighthunt_ws_runtime_errors" "count"
        EarlyCloses = MetricValue $metrics "nighthunt_ws_early_closes" "count"
        HttpFailedRate = RateValue $metrics "http_req_failed"
        HttpReqs = MetricValue $metrics "http_reqs" "count"
        WsSessions = MetricValue $metrics "ws_sessions" "count"
        WsSessionP95Ms = MetricValue $metrics "ws_session_duration" "p(95)"
        MessagesSent = MetricValue $metrics "nighthunt_ws_messages_sent" "count"
        MessagesReceived = MetricValue $metrics "nighthunt_ws_messages_received" "count"
    }
}

$rows | Format-Table -AutoSize
