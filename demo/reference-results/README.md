# Reference results

Analyzer output behind the README's results table, so the numbers can be checked without re-running the stack.
Measured 2026-09-23 on OBI v0.10.0, Ktor 3.2.2, kotlinx-coroutines 1.10.2, JDK 21, Linux 6.12, with the agent at the commit that last changed this directory.
Raw Jaeger JSON is not committed (`demo/results*/` is ignored); `make load RESULTS=<dir>` regenerates it.

| file | condition |
|---|---|
| `sequential-netty-nio.txt` | agent on, Netty NIO (default): `make up`, `make load`, `make analyze` |
| `sequential-netty-epoll.txt` | agent on, Netty epoll: `make up-epoll` |
| `sequential-netty-epoll-baseline.txt` | no agent, Netty epoll (baseline for the row above) |
| `sequential-cio.txt` | agent on, CIO server engine: `make up-cio` |
| `sequential-vt-control.txt` | the `java` block is the plain-Java control on a virtual-thread executor: `make up-vt` |
| `keepalive-nio.txt`, `keepalive-cio.txt` | agent on, 10 requests per endpoint over one connection (`curl` with several URLs) |
| `concurrent-<engine>-r<n>.txt` | agent on, `make load-concurrent` / `make analyze-concurrent`, run n |
| `overhead-round1.md`, `overhead-round2.md` | `demo/run_overhead.sh` agent-off vs agent-on, 4000 requests at concurrency 16 after a 300-request warm-up |

The sequential baselines for Netty NIO (0/10) and CIO (3/10) were measured in July and August 2026 with the same scripts; they do not depend on the agent.
Keep-alive without the agent was 0/10 on every endpoint.
