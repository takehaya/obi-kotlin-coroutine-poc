#!/bin/bash
# Builds everything: the agent (fat jar + boot jar + JNI lib) and the demo services.
set -eu
cd "$(dirname "$0")"

./gradlew --no-daemon :agent:assemble :frontend:installDist :backend:installDist :jfront:installDist

mkdir -p agent/dist
cp agent/build/libs/coroagent.jar agent/build/libs/coroagent-boot.jar agent/dist/
agent/build-native.sh

echo "Build complete. Agent artifacts are in agent/dist/"
