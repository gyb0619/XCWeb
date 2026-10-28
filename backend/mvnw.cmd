@echo off
setlocal
set "LOCAL_MVN=C:\Users\CaYPt\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd"
if exist "%LOCAL_MVN%" (
    call "%LOCAL_MVN%" %*
    exit /b %ERRORLEVEL%
)
where mvn >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    call mvn %*
    exit /b %ERRORLEVEL%
)
echo Error: Maven (mvn) not found in PATH or wrapper directory.
exit /b 1
