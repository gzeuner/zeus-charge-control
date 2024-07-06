
# Zeus Charge Control

Zeus Charge Control - A Java application for managing battery charging schedules based on market prices. This application supports the Sonnen API v2 for battery management.

## Features

- **Battery Status**: Monitor the current status of your battery.
- **Best Price in Scope**: Identify the best market prices for charging within a specified timeframe.
- **Price Chart**: Visualize market prices over time.

## Files Included

- `zeus-charge-control-1.0.jar`: The main executable JAR file.
- `zeus-charge-control.bat`: Windows script to set environment variables and run the application.
- `zeus-charge-control.sh`: Linux script to set environment variables and run the application.
- `license_de.html`: License file in German.
- `license_en.html`: License file in English.
- `LICENSE`: License file in plain text.

## Usage

### Windows

1. Edit `zeus-charge-control.bat` to set the environment variables.
2. Double-click `zeus-charge-control.bat` to run the application.

### Linux

1. Edit `zeus-charge-control.sh` to set the environment variables.
2. Make the script executable:
   ```sh
   chmod +x zeus-charge-control.sh
   ```
3. Run the script:
   ```sh
   ./zeus-charge-control.sh
   ```
### Service

The Service can be accessed via http://localhost:8080/charging-status

### Download Binary

You can download the latest version of Zeus Charge Control from the following link:

[Zeus Charge Control v1.0](https://github.com/gzeuner/zeus-charge-control/releases/download/v1.0/zeus-charge-control.zip)

## Screenshots

Here are some screenshots of the application in action:

**Battery Status**

<img src="images/battery_status.jpg" alt="images/battery_status.jpg" width="60%">

**Best Price in Scope**

<img src="images/best_price_in_scope.jpg" alt="best_price_in_scope.jpg" width="60%">

**Price Chart**

<img src="images/price_chart.jpg" alt="price_chart.jpg" width="60%">

## Requirements

The battery must support the Sonnen API v2. Other APIs are currently not supported.

Java 16 and above.

### Download Binary

You can download the latest version of Zeus Charge Control from the following link:

[Zeus Charge Control v1.0](https://github.com/gzeuner/zeus-charge-control/releases/download/v1.0/zeus-charge-control.zip)

### Environment Variables and Their Significance

Our application uses a variety of environment variables to adapt flexibly to different configurations and scenarios. Here are some of the most important variables and their meanings:

- **SERVER_ADDRESS**: This variable specifies the IP address on which the server accepts requests. By default, it is set to `0.0.0.0`, which means the server listens on all available network addresses.

- **SERVER_PORT**: This variable sets the port on which the server runs. The default value is `8080`.

- **BATTERY_URL**: The URL of the battery API. This API is used to query the current status of the battery and send charging commands.

- **BATTERY_AUTH_TOKEN**: The authentication token for the battery API. This token is required to send authorized requests to the API.

- **BATTERY_STATE_OF_CHARGE**: This variable specifies the starting state of charge of the battery in percentage. It determines at what state of charge the battery should begin a favorable charging cycle. If this variable is not set, a default value of 30% is used.

- **BATTERY_TARGET_STATE_OF_CHARGE**: This variable specifies the target state of charge of the battery in percentage. It determines up to what state of charge the battery should be charged. Once this value is reached, the charging process stops. If this variable is not set, a default value of 100% is used.

- **BATTERY_CHARGING_POINT**: This variable specifies the charging point of the battery in watts. It determines the power at which the battery should be charged to achieve an optimal balance between charging time and energy efficiency. If this variable is not set, a default value of 4000 W is used.

- **AWATTAR_MARKETDATA_URL**: The URL of the aWATTar market price service. This URL is used to retrieve current and future electricity prices from aWATTar.

- **AWATTAR_AUTH_TOKEN**: The authentication token for the aWATTar market price service. Currently, aWATTar provides prices publicly under the fair use principle without access restrictions.

- **TIBBER_MARKETDATA_URL**: The URL of the Tibber market price service. This URL is used to retrieve current and future electricity prices from Tibber.

- **TIBBER_AUTH_TOKEN**: The authentication token for the Tibber market price service. This token is required to send authorized requests to the Tibber API. Tibber customers can obtain electricity prices from the Tibber API.

- **MARKETDATA_SOURCE**: This variable specifies which data source to use for market prices. Possible values are `awattar` or `tibber`. Depending on the selected source, the application will retrieve market prices either from aWATTar or Tibber.


## License

This software is provided under a proprietary license.

It includes software developed by the Spring Boot project <a href="http://spring.io/projects/spring-boot">(http://spring.io/projects/spring-boot)</a>.
Spring Boot components are licensed under the Apache License, Version 2.0 <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a>

For license details, see `LICENSE.txt`, `license_de.html`, or `license_en.html`.

For more details, refer to the provided license files.
