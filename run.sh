#!/bin/sh
# Starts Valheim Mod Manager from source.
cd "$(dirname "$0")" && exec ./gradlew -q run
