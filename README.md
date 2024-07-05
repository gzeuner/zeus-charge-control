
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

## License

This software is provided under a proprietary license.

It includes software developed by the Spring Boot project <a href="http://spring.io/projects/spring-boot">(http://spring.io/projects/spring-boot)</a>.
Spring Boot components are licensed under the Apache License, Version 2.0 <a href="http://www.apache.org/licenses/LICENSE-2.0">(http://www.apache.org/licenses/LICENSE-2.0)</a>

For license details, see `LICENSE.txt`, `license_de.html`, or `license_en.html`.

For more details, refer to the provided license files.
