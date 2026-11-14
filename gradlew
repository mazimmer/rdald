#!/usr/bin/env bash

set -e

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

if [[ -n "${JAVA_HOME}" ]] ; then
    JAVACMD="${JAVA_HOME}/bin/java"
else
    JAVACMD="$(which java)"
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1 ; then
    echo "Could not find Java executable. Please install Java and/or set the JAVA_HOME environment variable." >&2
    exit 1
fi

exec "$JAVACMD" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
