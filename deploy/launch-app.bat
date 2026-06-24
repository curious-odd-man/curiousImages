@echo off
SET APP_DIR=${deployDir}
SET JAR=%APP_DIR%\\${jarName}
SET DATA_DIR=%USERPROFILE%\\AppData\\Local\\cImages\\data
SET LOG_DIR=%APP_DIR%\\logs

if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

javaw ^
    -XX:+UseZGC ^
    -Xmx4g ^
    -D"spring.profiles.active"=prod ^
    -jar "%JAR%"

exit
