#!/bin/bash
# Fails if an agent hook stops matching: starts the frontend with the agent on each server setup
# (no Docker, no OBI; the ioctls just fail), sends a request, and checks the agent's
# "transformed" log for the classes each setup depends on and for transformation errors.
# Needs ./build.sh output and a free port 8080.
set -u
cd "$(dirname "$0")/.."
AGENT="-javaagent:$PWD/agent/dist/coroagent.jar -Dobicoro.bootjar=$PWD/agent/dist/coroagent-boot.jar -Dobicoro.native=$PWD/agent/dist/libcoroagent.so"
LOG=$(mktemp)
fail=0

check() {  # name env expected-classes...
    local name=$1 env=$2; shift 2
    env $env OBICORO_DEBUG=1 JAVA_OPTS="$AGENT" demo/frontend/build/install/frontend/bin/frontend > "$LOG" 2>&1 &
    local pid=$!
    for _ in $(seq 60); do curl -s -o /dev/null http://localhost:8080/direct && break; sleep 0.5; done
    curl -s -o /dev/null http://localhost:8080/hop
    kill "$pid"; wait "$pid" 2>/dev/null
    for c in "$@"; do
        if ! grep -qF "[obicoro] transformed $c" "$LOG"; then
            echo "FAIL $name: $c was not transformed"; fail=1
        fi
    done
    if grep -q '\[obicoro\] transform error' "$LOG"; then
        echo "FAIL $name: transformation errors:"; grep '\[obicoro\] transform error' "$LOG"; fail=1
    fi
    echo "checked $name"
}

COMMON="kotlinx.coroutines.internal.DispatchedContinuation"
check nio   "KTOR_ENGINE=" "$COMMON" 'io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe' io.ktor.server.netty.NettyApplicationCallHandler
check epoll "NETTY_TRANSPORT=epoll" "$COMMON" 'io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe'
check cio   "KTOR_ENGINE=cio" "$COMMON" io.ktor.network.sockets.NIOSocketImpl io.ktor.server.cio.backend.ServerPipelineKt
rm -f "$LOG"
exit $fail
