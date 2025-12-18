@echo off

REM Set environment variables
set BATTERY_URL=your_battery_ip/api/v2
set BATTERY_AUTH_TOKEN=your_battery_auth_token
set BATTERY_TARGET_STATE_OF_CHARGE=80
set BATTERY_CHARGING_POINT=4500

REM awattar or tibber
set MARKETDATA_SOURCE=awattar

set AWATTAR_AUTH_TOKEN=your_awattar_auth_token
set TIBBER_AUTH_TOKEN=your_tibber_auth_token

REM Run the Java application
java -jar zeus-charge-control-3.0.jar


