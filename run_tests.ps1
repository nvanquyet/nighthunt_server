# Backend Test Execution Script (PowerShell)
# Run this script from NightHuntServer directory

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "  NightHunt Backend Test Suite Execution" -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host ""


$tests = @(
    @{Name="FriendServiceTest"; Count=19},
    @{Name="PartyServiceTest"; Count=18},
    @{Name="PartyMatchmakingServiceTest"; Count=17},
    @{Name="PartyRoomServiceTest"; Count=13},
    @{Name="GameModeServiceTest"; Count=21},
    @{Name="SocialSystemIntegrationTest"; Count=9}
)

$totalTests = 0
$passedTests = 0
$failedTests = @()

for ($i = 0; $i -lt $tests.Length; $i++) {
    $test = $tests[$i]
    $current = $i + 1
    $total = $tests.Length
    
    Write-Host "[$current/$total] Running $($test.Name)..." -ForegroundColor Yellow
    
    # Run the test
    $output = & mvn test "-Dtest=$($test.Name)" -q 2>&1
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[✓] $($test.Name) passed ($($test.Count) tests)" -ForegroundColor Green
        $passedTests += $test.Count
    } else {
        Write-Host "[✗] $($test.Name) FAILED!" -ForegroundColor Red
        $failedTests += $test.Name
    }
    
    $totalTests += $test.Count
    Write-Host ""
}

Write-Host "====================================================" -ForegroundColor Cyan

if ($failedTests.Count -eq 0) {
    Write-Host "  ALL TESTS PASSED! ($totalTests tests)" -ForegroundColor Green
    Write-Host "====================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Test Summary:" -ForegroundColor White
    foreach ($test in $tests) {
        Write-Host "  - $($test.Name): $($test.Count) tests" -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "Backend is ready for deployment!" -ForegroundColor Green
    Write-Host ""
} else {
    Write-Host "  TEST EXECUTION FAILED" -ForegroundColor Red
    Write-Host "====================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Failed tests:" -ForegroundColor Red
    foreach ($failed in $failedTests) {
        Write-Host "  - $failed" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "Passed: $passedTests/$totalTests tests" -ForegroundColor Yellow
    Write-Host ""
}


if ($failedTests.Count -gt 0) {
    exit 1
}
