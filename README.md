# obi-kotlin-coroutine-poc

Proof of concept: making [OpenTelemetry eBPF Instrumentation (OBI)](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation) correlate traces across **Kotlin coroutine** suspension points — without modifying OBI.

This repo is the reproduction companion for an upstream proposal to add first-class Kotlin coroutine support to OBI's in-process trace correlation.

## The problem

OBI associates a service's incoming request with its outgoing calls through thread identity (plus clone-chain lookups, `Executor` instrumentation in its embedded Java agent, and virtual-thread mount tracking since v0.10.0). Kotlin coroutines fall through all of these paths: `kotlinx.coroutines` dispatches work through its own scheduler, and a request's identity lives in heap `Continuation` objects rather than in any thread.

The result, measured with the demo in this repo (OBI v0.10.0, Ktor 3.2.2, plaintext HTTP):

- Every request through the coroutine-based frontend splits into two traces: an orphaned server span, and a separate trace holding the client span + the downstream service. 30/30 requests split across three endpoint shapes — including `/direct`, which contains no user-written suspension at all.
- A thread-per-request plain-Java control service on the same topology connects 10/10.

## The PoC

A standalone `-javaagent` (ByteBuddy + a ~40-line JNI shim) makes coroutines masquerade as virtual threads, using OBI's **existing** ioctl control channel (`k_ioctl_java_vt_mount` / `unmount`). OBI itself is unmodified; the ioctl kprobe accepts the ops because the process is already an instrumented target.

- **Lineage id**: identity hash of the incoming connection object.
- **Scopes** (where an id enters a thread): Netty `NioByteUnsafe.read` / `EpollStreamUnsafe.epollInReady` (bracket the recv syscall, keying the server-span insert; the NIO and epoll transports are covered, io_uring and KQueue are not), inbound handlers' `channelRead` (the cross-event-loop handoff is a hidden-class lambda and cannot be instrumented, so the receiving side recovers the id from `ctx.channel()`), and ktor-network `NIOSocketImpl.attachFor*Impl` with preserve-or-seed semantics (which also covers the CIO server engine).
- **Carry**: constructors of kotlinx/ktor `Runnable`s stamp the current id onto the task, once. A coroutine belongs to the request that launched it; re-stamping on dispatch would hand it the id of whichever request's thread happened to wake it.
- **Apply**: task `run()` entry mounts the id, exit restores the previous state — no state ever lingers on a thread.

### Results (fully connected traces)

| Condition | baseline | PoC |
|---|---|---|
| Netty engine (NIO), sequential `/direct`, `/hop` | 0/10 | **10/10** (client span correctly parented) |
| Netty engine (NIO), sequential `/parallel` (both calls) | 0/10 | **10/10** |
| Netty engine (epoll transport), sequential, all three endpoints | 0/10 | **10/10** |
| CIO engine, sequential, all three endpoints | 3/10 (coincidental thread reuse) | **10/10** |
| concurrency 16, `/direct` (200 req) | 0 | **136/200**, zero orphaned client spans |
| concurrency 16, `/hop` (200 req) | 0 | 47/200 |
| concurrency 8, `/parallel` (100 req) | 0 | 37/100 |

Controls under the same OBI, agent not involved: the thread-per-request plain-Java service connects 10/10, and so does the same service on a virtual-thread-per-task executor (OBI's own virtual-thread support, `make up-vt`).

The concurrent rows are a single run; three runs of the agent (July and September 2026) ranged 136–156 for `/direct`, 19–47 for `/hop` and 15–37 for `/parallel`. The analyzer output behind every row is in [`demo/reference-results/`](demo/reference-results/).

The concurrent `/hop` / `/parallel` residue is structural: the CIO client's connection pool uses a long-lived per-connection writer coroutine whose single `run()` drains writes for multiple requests, and a per-thread, per-instant mount can only name one request for the whole slice. Fixing that requires correlation state keyed by logical task (continuation) — which is the upstream proposal, and the same shape OBI already uses for Python asyncio.

### Overhead

`demo/run_overhead.sh` drives `/direct` and `/hop` in turn at concurrency 16 (4000 requests each after a 300-request warm-up, Netty NIO) with and without the agent, and records latency percentiles, the frontend JVM's CPU time during the load and the `ioctl` syscalls it made. Two rounds:

| endpoint, round | p50 off → on | p99 off → on | req/s off → on | JVM CPU (cores) off → on | ioctl per request off → on |
|---|---|---|---|---|---|
| `/direct`, 1 | 25.7 → 26.2 ms | 40.6 → 43.3 ms | 598 → 586 | 7.27 → 7.74 | 7.0 → 74.6 |
| `/direct`, 2 | 27.8 → 24.6 ms | 38.5 → 47.3 ms | 559 → 625 | 6.77 → 6.29 | 7.0 → 74.7 |
| `/hop`, 1 | 54.5 → 55.2 ms | 63.3 → 65.3 ms | 289 → 284 | 2.16 → 2.57 | 7.0 → 83.3 |
| `/hop`, 2 | 56.0 → 54.9 ms | 71.1 → 65.9 ms | 281 → 285 | 2.39 → 2.67 | 7.0 → 83.5 |

Latency and throughput differences change sign between rounds, so they are within run-to-run noise. `/hop` costs 12–19% more JVM CPU with the agent in both rounds; on `/direct` the CPU difference is below the noise. The agent adds about 70 `ioctl` calls per request (two per task `run()`), each a syscall that OBI's kprobe consumes at entry and the kernel then rejects. The 7 per request without the agent are made by the JVM with OBI attached; their source was not investigated.

## Reproduction

Requirements: Linux kernel 5.8+ with BTF, Docker (compose v2), JDK 21 (with JNI headers), gcc, python3. OBI runs privileged.

Prefer a guided tour? [PLAYGROUND.md](PLAYGROUND.md) walks through a 10-minute baseline-vs-agent comparison of the same steps, with `make` shortcuts for each command.

```bash
# 1. Build everything (services, agent jars, JNI lib)
./build.sh

# 2. Start the stack: frontend/backend (Ktor), jfront (plain-Java control), Jaeger, OBI.
#    Without FRONTEND_JAVA_OPTS you get the baseline (split traces).
cd demo
AGENT="-javaagent:/coroagent/coroagent.jar -Dobicoro.native=/coroagent/libcoroagent.so"
FRONTEND_JAVA_OPTS="$AGENT" sudo -E docker compose up -d

# 3. Drive the four conditions and summarize trace compositions from the Jaeger API
./run_conditions.sh
python3 analyze_jaeger.py results

# Concurrent load variant
./run_concurrent.sh results-concurrent
python3 analyze_concurrent.py results-concurrent
```

Variants are switched with an environment variable on the `up` line (e.g. `KTOR_ENGINE=cio FRONTEND_JAVA_OPTS="$AGENT" sudo -E docker compose up -d --force-recreate`), or with the `make` shortcut, which re-creates the stack with the agent attached:

- `KTOR_ENGINE=cio` — frontend server engine, Netty by default (`make up-cio`).
- `NETTY_TRANSPORT=epoll` — Netty's native epoll transport, NIO by default (`make up-epoll`).
- `JFRONT_EXECUTOR=virtual` — plain-Java control service on a JDK 21 virtual-thread-per-task executor (`make up-vt`).
- `OBICORO_DEBUG=1` — agent debug logging, see below.

The load and analysis steps have shortcuts too: `make load RESULTS=<dir>` / `make analyze RESULTS=<dir>`, and `make load-concurrent` / `make analyze-concurrent`.

Overhead: start the stack without the agent (`make up-baseline`), run `demo/run_overhead.sh results-overhead agent-off`, restart with the agent (`make up`), run the same script with `agent-on`, then `python3 demo/summarize_overhead.py results-overhead`. It needs `bpftrace` on the PATH (or `BPFTRACE=...`) for the ioctl count.

Traces are also browsable in the Jaeger UI at http://localhost:16686 (compare service `frontend` with the control `jfront`). `OBICORO_DEBUG=1` makes the agent log mounts/stamps to stderr, plus one `transformed <class>` line for every class it instruments (useful to check a hook still matches after a Netty/Ktor upgrade). Transformation errors are always logged, with or without the variable.

## Layout

```
agent/   the -javaagent (ByteBuddy instrumentation, JNI shim for gettid/ioctl)
demo/    4-service topology (compose), OBI config, load & analysis scripts
```

## Known limitations

This is a proof of concept, not a production agent.

- Concurrent traffic multiplexed over pooled client connections cannot be fully attributed (see above); a small rate of mis-parented client spans remains under load.
- Scope hooks cover Ktor's Netty and CIO engines and the CIO client. On Netty only the NIO and epoll transports are hooked; io_uring and KQueue are not. Other engines/clients/transports need their own scope hooks.
- The lineage id (31-bit identity hash) can collide in principle.
- Tied to OBI v0.10.0's ioctl ABI.
- Only plaintext HTTP/1.1 has been measured. TLS (which goes through OBI's SSL path), HTTP/2 and gRPC are untested.
- Only Ktor has been exercised. Spring WebFlux with coroutines and other coroutine-based stacks are untested.
- The JNI shim is compiled on the host for x86_64 glibc; other architectures or musl-based images need a rebuild.
- The agent writes the same `java_vt_threads` entry as OBI's own virtual-thread instrumentation. A JVM that runs virtual threads and coroutines on the same carrier threads would have the two overwrite each other; this combination has not been measured.

## License

Apache-2.0
