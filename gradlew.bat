@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off

set APP_HOME=%~dp0

if defined JAVA_HOME (
    set JAVA="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVA="java.exe"
)

if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
    %JAVA% -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    gradle %*
)
