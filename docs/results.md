# Results

Counts are fully connected traces (server span, client span and the backend span in one trace).

## OBI v1.0.0 (2026-11-12)

Measured on Linux 7.0 with OBI running on the host. Analyzer output: [`demo/reference-results/obi-v1.0.0/`](../demo/reference-results/obi-v1.0.0/).

| Condition | baseline | PoC |
|---|---|---|
| Sequential, Netty NIO and epoll, `/direct` `/hop` `/parallel` | 0/10 | **10/10** |
| Sequential, CIO | 1–9/10 (coincidental thread reuse) | **10/10** |
| keep-alive (10 requests per connection) | 0/10 | **10/10** (NIO, CIO) |
| Concurrency 16 `/direct` and `/hop` (200 each) | 2/200, 1/200 (NIO) | **200/200** (NIO, epoll, CIO) |
| Concurrency 8 `/parallel` (100) | 3/100 (NIO) | **100/100** (NIO, epoll, CIO) |
| `/shared` (one worker started by the first request) | not measured | 1/10 (only the first request) |
| Plain-Java control | 10/10 | 10/10 |

## OBI v0.10.0 (September 2026)


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
| `/vt`: backend call in a coroutine on a virtual-thread dispatcher, sequential and concurrency 16 | 0/10, 4/200 | **10/10, 200/200** |
| HTTPS to the backend (OkHttp, `BACKEND_TLS=1`), sequential `/direct`, `/hop` | 0/10 | **10/10** |

The concurrent rows held in three runs each on NIO and CIO and one on epoll, with no mis-parented client span in any of them. Controls under the same OBI, agent not involved: the thread-per-request plain-Java service connects 10/10, and so does the same service on a virtual-thread-per-task executor (OBI's own virtual-thread support, `make up-vt`). The analyzer output behind every row is in [`demo/reference-results/`](../demo/reference-results/).

Earlier versions of this README reported a concurrent residue (136–156/200 on `/direct`, 19–47/200 on `/hop`) and blamed the CIO client's connection pool. That was wrong: by default the CIO client opens one connection per call (`pipelining` is off), and a bpftrace count of `tcp_v4_connect` matched the number of backend calls. The residue came from the agent re-stamping tasks on `CoroutineDispatcher.dispatch` with whichever request's id the waking thread held, and on CIO from scheduler plumbing (`LimitedDispatcher$Worker`) carrying the first request's id forever.
