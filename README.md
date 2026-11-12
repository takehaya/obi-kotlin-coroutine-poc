# obi-kotlin-coroutine-poc

Try it in about 10 minutes (Linux with Docker, JDK 21, gcc):

1. `./build.sh`
2. `make up-baseline`, wait 30 seconds, then `curl localhost:8080/direct`
3. Open http://localhost:16686 and pick service `frontend`: one request shows up as **two** traces
4. `make down && make up`, wait 30 seconds, then `curl localhost:8080/direct` again
5. Refresh Jaeger: the request is now **one** trace, from the frontend through the backend

[PLAYGROUND.md](PLAYGROUND.md) is the guided version of these steps.

## What this shows

- [OpenTelemetry eBPF Instrumentation (OBI)](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation) links a service's incoming request to its outgoing calls by thread. Kotlin coroutines move a request across threads, so every request through a Ktor frontend splits, even `/direct`, which has no suspension of its own.
- A standalone `-javaagent` fixes the link without modifying OBI. It reuses OBI's existing virtual-thread mount ioctl, so the threads running a request's coroutines name that request while they run.
- This repo is the reproduction companion for an upstream proposal to give OBI first-class Kotlin coroutine support.

## Results

Measured with OBI v1.0.0 on 2026-11-12. Counts are fully connected traces.

| Condition | OBI alone | OBI + this agent |
|---|---|---|
| Sequential, Netty and CIO engines, three endpoints | 0/10 on Netty, 1–9/10 on CIO | 10/10 |
| Concurrency 8–16 (500 requests) | 6/500 | 500/500 |
| keep-alive (10 requests per connection) | 0/10 | 10/10 |
| One worker shared by all requests (`/shared`) | — | 1/10, a known limit |
| Cost (OBI v0.10.0, 16-vCPU VM) | — | CPU per request +4–12%, throughput −2% or less, p50 unchanged |

## More

| Topic | File |
|---|---|
| How the agent works | [docs/how-it-works.md](docs/how-it-works.md) |
| All results, OBI v1.0.0 and v0.10.0 | [docs/results.md](docs/results.md) |
| Overhead measurements | [docs/overhead.md](docs/overhead.md) |
| Running it by hand, variants, debugging | [docs/reproduce.md](docs/reproduce.md) |
| Known limitations | [docs/limitations.md](docs/limitations.md) |

## Requirements

- Linux kernel 5.8+ with BTF
- Docker with compose v2. OBI runs privileged and needs tracefs, which the compose file mounts
- JDK 21 with JNI headers, gcc, python3, curl

## Layout

```
agent/   the -javaagent (ByteBuddy instrumentation, JNI shim for gettid/ioctl)
demo/    4-service topology (compose), OBI config, load and analysis scripts
docs/    details moved out of this README
vm/      scripts for the dedicated-VM overhead runs
```

This is a proof of concept, not a production agent.

## License

Apache-2.0
