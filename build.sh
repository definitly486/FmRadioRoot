#!/bin/sh
JAVA_TOOL_OPTIONS="-Dos.name=Linux" ./gradlew assembleDebug --no-daemon "$@"