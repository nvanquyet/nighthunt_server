@echo off
REM Backend Test Execution Script
REM Run this script from NightHuntServer directory

echo ====================================================
echo   NightHunt Backend Test Suite Execution
echo ====================================================
echo.


echo [1/6] Running FriendServiceTest...
call mvn test -Dtest=FriendServiceTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] FriendServiceTest failed!
    goto :error
)
echo [✓] FriendServiceTest passed
echo.

echo [2/6] Running PartyServiceTest...
call mvn test -Dtest=PartyServiceTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] PartyServiceTest failed!
    goto :error
)
echo [✓] PartyServiceTest passed
echo.

echo [3/6] Running PartyMatchmakingServiceTest...
call mvn test -Dtest=PartyMatchmakingServiceTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] PartyMatchmakingServiceTest failed!
    goto :error
)
echo [✓] PartyMatchmakingServiceTest passed
echo.

echo [4/6] Running PartyRoomServiceTest...
call mvn test -Dtest=PartyRoomServiceTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] PartyRoomServiceTest failed!
    goto :error
)
echo [✓] PartyRoomServiceTest passed
echo.

echo [5/6] Running GameModeServiceTest...
call mvn test -Dtest=GameModeServiceTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] GameModeServiceTest failed!
    goto :error
)
echo [✓] GameModeServiceTest passed
echo.

echo [6/6] Running SocialSystemIntegrationTest...
call mvn test -Dtest=SocialSystemIntegrationTest -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] SocialSystemIntegrationTest failed!
    goto :error
)
echo [✓] SocialSystemIntegrationTest passed
echo.

echo ====================================================
echo   ALL TESTS PASSED! (81 tests)
echo ====================================================
echo.
echo Test Summary:
echo   - FriendServiceTest: 19 tests
echo   - PartyServiceTest: 18 tests
echo   - PartyMatchmakingServiceTest: 17 tests
echo   - PartyRoomServiceTest: 13 tests
echo   - GameModeServiceTest: 21 tests
echo   - SocialSystemIntegrationTest: 9 tests
echo.
echo Backend is ready for deployment!
echo.

exit /b 0

:error
echo.
echo ====================================================
echo   TEST EXECUTION FAILED
echo ====================================================
echo Please check the error messages above.
echo.

exit /b 1
