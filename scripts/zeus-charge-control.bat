@echo off
setlocal DisableDelayedExpansion

rem The same launcher works inside the distribution and from the source checkout.
set "APP_HOME=%~dp0"
set "APP_JAR=%~dp0lib\zeus-charge-control.jar"
if exist "%APP_JAR%" goto packaged

set "APP_HOME=%~dp0.."
set "APP_JAR="
set "JAR_COUNT=0"
for %%J in ("%APP_HOME%\target\zeus-charge-control-*.jar") do if exist "%%~fJ" (
    set /a JAR_COUNT+=1 >nul
    set "APP_JAR=%%~fJ"
)
if not "%JAR_COUNT%"=="1" (
    echo Expected one application JAR in target. Run mvn clean verify first. 1>&2
    exit /b 2
)
goto launch

:packaged
if not exist "%APP_HOME%config\application.properties" (
    echo Configuration missing. Copy config\application.properties.example to config\application.properties and review its settings. 1>&2
    exit /b 2
)

:launch
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
pushd "%APP_HOME%" || exit /b 2
"%JAVA_BIN%" -jar "%APP_JAR%" "--spring.config.additional-location=optional:file:./config/" %*
set "APP_EXIT=%ERRORLEVEL%"
popd
exit /b %APP_EXIT%
