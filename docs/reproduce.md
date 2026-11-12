# Reproducing by hand

Requirements: Linux kernel 5.8+ with BTF, Docker (compose v2), JDK 21 (with JNI headers), gcc, python3. OBI runs privileged.

Prefer a guided tour? [PLAYGROUND.md](../PLAYGROUND.md) walks through the same steps with `make` shortcuts.

```bash
# 1. Build everything (services, agent jars, JNI lib)
./build.sh

# 2. Start the stack: frontend/backend (Ktor), jfront (plain-Java control), Jaeger, OBI.
#    Without FRONTEND_JAVA_OPTS you get the baseline (split traces).
cd demo
AGENT="-javaagent:/coroagent/coroagent.jar -Dobicoro.native=/coroagent/libcoroagent.so"
sudo -E FRONTEND_JAVA_OPTS="$AGENT" docker compose up -d   # pass the variable as a sudo argument; some sudo builds (sudo-rs) drop it otherwise

# 3. Drive the four conditions and summarize trace compositions from the Jaeger API
./run_conditions.sh
python3 analyze_jaeger.py results

# Concurrent load variant
./run_concurrent.sh results-concurrent
python3 analyze_concurrent.py results-concurrent
```

Variants are switched with an environment variable on the `up` line (e.g. `sudo -E KTOR_ENGINE=cio FRONTEND_JAVA_OPTS="$AGENT" docker compose up -d --force-recreate`), or with the `make` shortcut, which re-creates the stack with the agent attached:

- `KTOR_ENGINE=cio` — frontend server engine, Netty by default (`make up-cio`).
- `NETTY_TRANSPORT=epoll` — Netty's native epoll transport, NIO by default (`make up-epoll`).
- `JFRONT_EXECUTOR=virtual` — plain-Java control service on a JDK 21 virtual-thread-per-task executor (`make up-vt`).
- `BACKEND_TLS=1 BACKEND_URL=https://backend:8443 CLIENT_ENGINE=okhttp` — HTTPS from the frontend to the backend (self-signed certificate).
- `CLIENT_ENGINE=okhttp` or `java` — the frontend's backend client (CIO by default); `CLIENT_POOL=<n>` turns on CIO pipelining over n connections; `BACKEND_ENGINE=cio` switches the backend's server engine.
- `OBICORO_DEBUG=1` — agent debug logging, see below.

The load and analysis steps have shortcuts too: `make load RESULTS=<dir>` / `make analyze RESULTS=<dir>`, and `make load-concurrent` / `make analyze-concurrent`.

Overhead measurement: see [overhead.md](overhead.md).

`make check-hooks` (also run in CI) starts the frontend with the agent on the NIO, epoll and CIO setups without Docker or OBI, and fails if a class the agent depends on is no longer transformed or a transformation error appears.

Traces are also browsable in the Jaeger UI at http://localhost:16686 (compare service `frontend` with the control `jfront`). `OBICORO_DEBUG=1` makes the agent log mounts/stamps to stderr, plus one `transformed <class>` line for every class it instruments (useful to check a hook still matches after a Netty/Ktor upgrade). Transformation errors are always logged, with or without the variable.
