# =============================================================================
# NightHunt - Production Stack Down
# Stops the direct-Docker production containers used by localhost HTTPS checks.
# =============================================================================

param([switch]$Volumes)

$containers = @('nighthunt-backend', 'nighthunt-mysql', 'nighthunt-redis', 'nighthunt-dashboard')
$volumes = @('nighthunt_mysql_data', 'nighthunt_redis_data')

Write-Host ""
Write-Host "NightHunt production stack down" -ForegroundColor Cyan

foreach ($container in $containers) {
    docker stop $container 2>$null *> $null
    docker rm $container 2>$null *> $null
}

if ($Volumes) {
    $confirm = Read-Host "Remove MySQL and Redis volumes as well? (y/N)"
    if ($confirm -eq 'y' -or $confirm -eq 'Y') {
        foreach ($volume in $volumes) {
            docker volume rm $volume 2>$null *> $null
        }
        Write-Host "Data volumes removed." -ForegroundColor Yellow
    }
}

Write-Host "Containers stopped." -ForegroundColor Green
