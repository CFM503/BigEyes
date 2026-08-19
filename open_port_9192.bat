@echo off
fltmc >nul 2>&1 || (
    powershell -Command "Start-Process '%~dpnx0' -Verb RunAs"
    exit /b
)

echo [1/2] Opening Port 9192 (TCP) Inbound...
netsh advfirewall firewall delete rule name="Port_9192_TCP" >nul 2>&1
netsh advfirewall firewall add rule name="Port_9192_TCP" dir=in action=allow protocol=TCP localport=9192 profile=any

echo [2/2] Opening Port 9192 (UDP) Inbound...
netsh advfirewall firewall delete rule name="Port_9192_UDP" >nul 2>&1
netsh advfirewall firewall add rule name="Port_9192_UDP" dir=in action=allow protocol=UDP localport=9192 profile=any

echo.
echo ========================================================
echo  Port 9192 (TCP and UDP) has been successfully opened!
echo ========================================================
echo.
pause
