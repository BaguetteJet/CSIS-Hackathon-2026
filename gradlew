#!/bin/sh
#
# Gradle wrapper script for POSIX-compliant systems.
#

# Determine the script directory
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="Gradle"

# Find JAVA_HOME
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

# Run the wrapper jar if present, otherwise fall back to system gradle
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec "$JAVA" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        org.gradle.wrapper.GradleWrapperMain "$@"
else
    exec gradle "$@"
fi
