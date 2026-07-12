#!/bin/bash
# Builds the tiny JNI bridge (gettid + the OBI ioctl channel).
set -eu
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi

mkdir -p dist
gcc -shared -fPIC -O2 -o dist/libcoroagent.so native/coroagent.c \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux"
echo "built agent/dist/libcoroagent.so"
