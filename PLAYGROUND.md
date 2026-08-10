# Playground

A guided walkthrough of the demo: first watch OBI split Kotlin coroutine traces, then watch the PoC agent reconnect them.
It takes about 10 minutes once the toolchain is installed; the first build also downloads Gradle and all dependencies, which can take considerably longer.

All commands run from the repository root through the [Makefile](Makefile), which wraps the same commands documented in the [README](README.md).

## Prerequisites

- Linux, kernel 5.8+ with BTF (`/sys/kernel/btf/vmlinux` exists).
- Docker with the compose v2 plugin.
- JDK 21 (with JNI headers), gcc, python3, curl.
- `sudo`: the stack is started with `sudo -E docker compose`, and the OBI container runs privileged with `pid: host` and host networking, because it loads eBPF programs into your kernel.
- Free host ports: 8080 (frontend), 8082 (jfront), 16686 (Jaeger UI), 4318 (OTLP).

OBI is pinned to `v0.10.0`.
The agent talks to OBI through that version's ioctl ABI, so don't bump the image tag and expect the agent half of this walkthrough to keep working.

## 1. Build everything

```bash
make build
```

This builds the three demo services, the agent jars, and the JNI library (into `agent/dist/`).

## 2. Start the baseline stack, without the agent

```bash
make up-baseline
```

This starts five containers: `frontend` (Ktor + coroutines, port 8080), `backend`, `jfront` (a thread-per-request plain-Java control service, port 8082), Jaeger, and OBI.
The frontend runs without the agent, so what you see next is stock OBI behavior on coroutine code.
Give OBI a few seconds to discover and instrument the JVMs before moving on.

## 3. Generate load

```bash
make load
```

This sends 10 sequential requests to each of four conditions (`/direct`, `/hop`, `/parallel` on the frontend, `/call` on the control service), waits 20 s for OBI's batch flush, then saves the traces from the Jaeger API into `demo/results/` (overwriting the previous run).
It takes about a minute.

## 4. See the split

```bash
make analyze
```

The analyzer groups spans into traces per condition and prints each distinct trace composition with its count.
On the baseline, every frontend condition splits into two populations of 10 traces: one holding the orphaned server span (plus OBI's internal child spans), and one holding the client span plus the backend.
The `direct` block has this shape (exact operation names, and any OBI-internal child spans, depend on the OBI version; the grouping is what matters):

```
===== condition: direct =====
   10 traces: ['frontend/internal:in queue', 'frontend/internal:processing', 'frontend/server:GET /direct']
   10 traces: ['backend/server:GET /work', 'frontend/client:GET /work']
```

`hop` and `parallel` split the same way, so all 30 frontend requests are broken.
Note that `direct` contains no user-written suspension at all; the coroutine machinery alone is enough to defeat thread-based correlation.
The control condition connects, because jfront handles each request on a single thread:

```
===== condition: java =====
   10 traces: ['backend/server:GET /work', 'jfront/client:GET /work', 'jfront/internal:in queue', 'jfront/internal:processing', 'jfront/server:GET /call']
```

This is the baseline column of the README's results table: 0/10 connected for the coroutine frontend, 10/10 for the plain-Java control.

## 5. Look at it in Jaeger

Open http://localhost:16686.
Search for service `frontend`: each `GET /direct` trace is a lone server span, and next to it sits a sibling trace that starts at a client span and continues into `backend`.
Same request, two trace IDs.
Then search for service `jfront`: one trace per request, with the server span on top and the client span and `backend` underneath.

## 6. Restart with the agent

```bash
make down
make up
```

`make up` sets `FRONTEND_JAVA_OPTS` so the frontend JVM loads the coroutine agent; everything else is identical to the baseline.
`make down` also discards Jaeger's in-memory trace store, so the second run starts from a clean slate.

## 7. Load and analyze again

```bash
make load
make analyze
```

Now each frontend condition shows a single population of 10 fully connected traces, matching the PoC column of the README's table:

```
===== condition: direct =====
   10 traces: ['backend/server:GET /work', 'frontend/client:GET /work', 'frontend/internal:in queue', 'frontend/internal:processing', 'frontend/server:GET /direct']
```

`hop` and `parallel` show the same connected shape under their own route names (`parallel` makes two client calls per request, but the analyzer lists the distinct span labels of a trace, so the duplicates collapse into one entry).
The `java` block is unchanged.

## 8. Look at it in Jaeger again

Back in the UI, search for service `frontend`.
Each trace now has the server span parenting the client span, with the backend's server span below it: the same shape the `jfront` control showed all along.

## Clean up

```bash
make down    # stop and remove the containers
make clean   # remove build outputs and downloaded results
```

## Where to go next

The sequential conditions above are the easy part; the interesting limits show up under concurrency.

- `demo/run_concurrent.sh` and `demo/analyze_concurrent.py` reproduce the concurrent rows of the README's results table, including the pooled-connection residue that motivates the upstream proposal.
- `KTOR_ENGINE=cio` switches the frontend server engine from Netty to CIO (see the README for the compose invocation).
- Setting `OBICORO_DEBUG=1` before `make up` makes the agent log every mount and stamp to stderr.
