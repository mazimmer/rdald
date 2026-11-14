@echo off
setlocal

set APP_HOME=%~dp0

if not exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    echo Could not find Gradle wrapper JAR. >&2
    exit /b 1
)

java -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
