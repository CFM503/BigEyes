@echo off
fltmc >nul 2>&1 || (
    powershell -Command "Start-Process '%~dpnx0' -Verb RunAs"
    exit /b
)

echo [1/3] Setting Network Profile to Private...
powershell -Command "Get-NetConnectionProfile | Set-NetConnectionProfile -NetworkCategory Private"

echo [2/3] Enabling Network Discovery...
netsh advfirewall firewall set rule group="Network Discovery" new enable=Yes >nul 2>&1
netsh advfirewall firewall set rule group="????" new enable=Yes >nul 2>&1

echo [3/3] Allowing Kodi and SSDP in Windows Firewall...
netsh advfirewall firewall delete rule name="Kodi_DLNA_TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="Kodi_DLNA_UDP" >nul 2>&1
netsh advfirewall firewall delete rule name="SSDP_1900_UDP" >nul 2>&1

netsh advfirewall firewall add rule name="Kodi_DLNA_TCP" dir=in action=allow program="D:\soft\app\kodi\kodi.exe" enable=yes profile=any protocol=tcp
netsh advfirewall firewall add rule name="Kodi_DLNA_UDP" dir=in action=allow program="D:\soft\app\kodi\kodi.exe" enable=yes profile=any protocol=udp
netsh advfirewall firewall add rule name="SSDP_1900_UDP" dir=in action=allow localport=1900 protocol=udp enable=yes profile=any

echo.
echo ========================================================
echo  All Settings Applied Successfully!
echo  Network is now Private, and DLNA Firewall is open.
echo ========================================================
echo.
pause
