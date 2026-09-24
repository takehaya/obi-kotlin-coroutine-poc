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

- **Lineage id**: a sequential number assigned to each incoming connection object.
- **Scopes** (where an id enters a thread): Netty `NioByteUnsafe.read` / `EpollStreamUnsafe.epollInReady` (bracket the recv syscall, keying the server-span insert; the NIO and epoll transports are covered, io_uring and KQueue are not), inbound handlers' `channelRead` (the cross-event-loop handoff is a hidden-class lambda and cannot be instrumented, so the receiving side recovers the id from `ctx.channel()`), ktor-network `NIOSocketImpl.attachFor*Impl` with preserve-or-seed semantics, and the CIO server's `startServerConnectionPipeline`, which launches a connection's request pipeline from the accept loop and gets the id its socket was attached under.
- **Carry**: constructors of kotlinx/ktor `Runnable`s stamp the current id onto the task, once. A coroutine belongs to the request that launched it; re-stamping on dispatch would hand it the id of whichever request's thread happened to wake it.
- **Apply**: task `run()` entry mounts the id, exit restores the previous state — no state ever lingers on a thread.

### Results (fully connected traces)

| Condition | baseline | PoC |
|---|---|---|
| Netty engine (NIO), sequential `/direct`, `/hop`, `/parallel` | 0/10 | **10/10** (client span correctly parented) |
| Netty engine (epoll transport), sequential, same three endpoints | 0/10 | **10/10** |
| CIO engine, sequential, same three endpoints | 3/10 (coincidental thread reuse) | **10/10** |
| keep-alive (10 requests over one connection), NIO and CIO | 0/10 | **10/10** |
| concurrency 16, `/direct` (200 req), NIO / CIO / epoll | 0 | **200/200** |
| concurrency 16, `/hop` (200 req), NIO / CIO / epoll | 0 | **200/200** |
| concurrency 8, `/parallel` (100 req), NIO / CIO / epoll | 0 | **100/100** |
| OkHttp client instead of CIO, sequential and concurrency 16 | 0/10, 0 | **10/10, 200/200** |
| Java `HttpClient` instead of CIO, sequential | 0/10 | 0/10 (not supported) |
| `/shared`: backend called by one long-lived worker coroutine | 0/10 | 0/10 (expected limit, see below) |

The concurrent rows held in three runs each on NIO and CIO and one on epoll, with no mis-parented client span in any of them. Controls under the same OBI, agent not involved: the thread-per-request plain-Java service connects 10/10, and so does the same service on a virtual-thread-per-task executor (OBI's own virtual-thread support, `make up-vt`). The analyzer output behind every row is in [`demo/reference-results/`](demo/reference-results/).

Earlier versions of this README reported a concurrent residue (136–156/200 on `/direct`, 19–47/200 on `/hop`) and blamed the CIO client's connection pool. That was wrong: by default the CIO client opens one connection per call (`pipelining` is off), and a bpftrace count of `tcp_v4_connect` matched the number of backend calls. The residue came from the agent re-stamping tasks on `CoroutineDispatcher.dispatch` with whichever request's id the waking thread held, and on CIO from scheduler plumbing (`LimitedDispatcher$Worker`) carrying the first request's id forever.

### Overhead

`demo/run_overhead.sh` drives `/direct` and `/hop` in turn at concurrency 16 (4000 requests each after a 300-request warm-up, Netty NIO) with and without the agent, and records latency percentiles, the frontend JVM's CPU time during the load and the `ioctl` syscalls it made. Two rounds:

| endpoint, round | p50 off → on | p99 off → on | req/s off → on | JVM CPU (cores) off → on | ioctl per request off → on |
|---|---|---|---|---|---|
| `/direct`, 1 | 23.8 → 24.0 ms | 39.3 → 46.6 ms | 648 → 628 | 5.08 → 5.78 | 7.0 → 76.0 |
| `/direct`, 2 | 23.6 → 24.0 ms | 40.9 → 50.1 ms | 651 → 617 | 5.41 → 5.65 | 7.0 → 76.1 |
| `/hop`, 1 | 53.3 → 53.0 ms | 60.8 → 59.7 ms | 295 → 298 | 1.80 → 1.81 | 7.0 → 87.6 |
| `/hop`, 2 | 52.8 → 53.0 ms | 56.8 → 58.2 ms | 301 → 295 | 1.70 → 1.79 | 7.0 → 87.5 |

On `/direct` the agent costs about 0.3 ms at p50, 7–9 ms at p99 and 3–5% of throughput, in the same direction in both rounds; on `/hop` the difference is within noise. The agent adds 69–81 `ioctl` calls per request (two per task `run()`), each a syscall that OBI's kprobe consumes at entry and the kernel then rejects. The load is closed-loop at concurrency 16 and latency is dominated by the backend's 20 ms `delay`, so this setup only shows effects larger than roughly a millisecond. The 7 per request without the agent are made by the JVM with OBI attached; their source was not investigated.

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
- `CLIENT_ENGINE=okhttp` or `java` — the frontend's backend client (CIO by default); `CLIENT_POOL=<n>` turns on CIO pipelining over n connections; `BACKEND_ENGINE=cio` switches the backend's server engine.
- `OBICORO_DEBUG=1` — agent debug logging, see below.

The load and analysis steps have shortcuts too: `make load RESULTS=<dir>` / `make analyze RESULTS=<dir>`, and `make load-concurrent` / `make analyze-concurrent`.

Overhead: start the stack without the agent (`make up-baseline`), run `demo/run_overhead.sh results-overhead agent-off`, restart with the agent (`make up`), run the same script with `agent-on`, then `python3 demo/summarize_overhead.py results-overhead`. It needs `bpftrace` on the PATH (or `BPFTRACE=...`) for the ioctl count.

`make check-hooks` (also run in CI) starts the frontend with the agent on the NIO, epoll and CIO setups without Docker or OBI, and fails if a class the agent depends on is no longer transformed or a transformation error appears.

Traces are also browsable in the Jaeger UI at http://localhost:16686 (compare service `frontend` with the control `jfront`). `OBICORO_DEBUG=1` makes the agent log mounts/stamps to stderr, plus one `transformed <class>` line for every class it instruments (useful to check a hook still matches after a Netty/Ktor upgrade). Transformation errors are always logged, with or without the variable.

## Layout

```
agent/   the -javaagent (ByteBuddy instrumentation, JNI shim for gettid/ioctl)
demo/    4-service topology (compose), OBI config, load & analysis scripts
```

## Known limitations

This is a proof of concept, not a production agent.

- Requests that genuinely share a connection concurrently (HTTP pipelining, HTTP/2 streams) are unmeasured. A per-thread mount names one request at a time, so a single `run()` that serves several requests at once cannot be attributed; this is the case the upstream proposal (state keyed by logical task) is for. The demo could not exercise it: CIO client pipelining (`CLIENT_POOL=4`) fails for most requests even without OBI or the agent, against a Netty backend (78 of 100 returned 500) and a CIO backend (`BACKEND_ENGINE=cio`, 49 returned 500 and 18 timed out).
- A coroutine carries the id of the request it was launched in. A long-lived coroutine launched inside one request and later reused by others keeps the first request's id; `/shared` (an app-scoped worker started by the first request) reproduces this: its client spans lose their parent, 0/10.
- Backend clients: Ktor CIO and OkHttp are covered. The JDK's `HttpClient` (Ktor's Java engine) is not: it writes from its own selector thread inside `java.net.http`, and every request splits.
- Scope hooks cover Ktor's Netty and CIO engines and the CIO client. On Netty only the NIO and epoll transports are hooked; io_uring and KQueue are not. Other engines/clients/transports need their own scope hooks.
- Tied to OBI v0.10.0's ioctl ABI.
- Only plaintext HTTP/1.1 has been measured. TLS (which goes through OBI's SSL path), HTTP/2 and gRPC are untested.
- Only Ktor has been exercised. Spring WebFlux with coroutines and other coroutine-based stacks are untested.
- The JNI shim is compiled on the host for x86_64 glibc; other architectures or musl-based images need a rebuild.
- The agent writes the same `java_vt_threads` entry as OBI's own virtual-thread instrumentation. A JVM that runs virtual threads and coroutines on the same carrier threads would have the two overwrite each other; this combination has not been measured.

## License

Apache-2.0
