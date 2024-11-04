#!/bin/bash

# Set environment variables
export BATTERY_URL="your_battery_ip/api/v2"
export BATTERY_AUTH_TOKEN="your_battery_auth_token"
export BATTERY_TARGET_STATE_OF_CHARGE=80
export BATTERY_CHARGING_POINT=4500

# Set market data source: awattar or tibber
export MARKETDATA_SOURCE="awattar"

export AWATTAR_AUTH_TOKEN="your_awattar_auth_token"
export TIBBER_AUTH_TOKEN="your_tibber_auth_token"

# Run the Java application
java -jar zeus-charge-control-1.2.jar
