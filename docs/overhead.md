# Overhead

Measured with OBI v0.10.0 in September 2026. Not re-measured on v1.0.0.

Measured with `vm/measure.sh` on a dedicated 16-vCPU KVM guest (Ubuntu 24.04, kernel 6.8): the frontend on 8 vCPUs, the backend, Jaeger and OBI on 4, the load generator on 4. The VM's steal time stayed at or below 0.04% in every step, so the host did not interfere.

Closed loop: `demo/run_overhead.sh` drives `/direct` and `/hop` at concurrency 16, 4000 requests each after a 300-request warm-up, agent off and on, five rounds in alternating order. Median (min–max) over the rounds:

| | `/direct` off → on | `/hop` off → on |
|---|---|---|
| p50 latency | 24.18 → 24.62 ms | 55.87 → 56.03 ms |
| p99 latency | 38.9 → 41.0 ms | 68.7 → 78.7 ms |
| throughput | 605 → 596 req/s | 275.0 → 273.9 req/s |
| JVM CPU per request | 5.54 → 5.74 ms (5.67–5.82) | 7.52 → 8.39 ms (8.05–8.87) |
| `ioctl` per request | 7.0 → 77 | 7.0 → 84 |

Open loop on `/direct`, fixed arrival rates from a load generator container on the compose network (by IP, bypassing Docker's userland proxy):

| req/s | p50 off → on | p99 off → on | CPU per request off → on |
|---|---|---|---|
| 600 | 23.3 → 23.1 ms | 96 → 116 ms | 5.11 → 5.13 ms |
| 1200 | 21.8 → 21.8 ms | 31 → 35 ms | 1.55 → 1.75 ms |
| 1500 | 21.9 → 21.9 ms | 28 → 91 ms | 1.50 → 1.83 ms |
| 2000 | 21.4 → 21.3 ms | 42 → 32 ms | 1.62 → 1.73 ms |

With the agent the median does not move at any rate, CPU per request rises by 0–22% depending on the step (about 4% on `/direct` and 12% on `/hop` in the closed loop), and throughput drops by up to 2%. p99 moves more from step to step and is higher with the agent at some rates (28 → 91 ms at 1500 req/s); both configurations served 2000 req/s with p99 under 50 ms and no errors, SYN resends or listen-queue overflows. At 2500 and 3000 req/s the load generator's four processes fell behind their schedule equally with and without the agent, so capacity above 2000 req/s was not measured. The extra `ioctl` calls are two per task `run()`, each a few microseconds (under 64 µs, measured with bpftrace on the workstation); the 7 per request without the agent come from OBI's own injected Java agent (0 with OBI stopped; JFR shows them in `io.opentelemetry.obi.java.Agent$NativeLib.ioctl`).

An earlier version of the agent kept its object-to-id maps behind one lock. It left the median alone but stretched the tail: at 2000 req/s JFR recorded 1,607 monitor waits of 10 ms or more in 20 s inside the agent, and p95 rose from 26 to 106 ms. The maps are now split into 64 stripes, which brought those waits down to 12.

CPU per request depends on how many cores the frontend has, because kotlinx.coroutines' scheduler spends idle time hunting for work on each of them (`WorkQueue.tryStealLastScheduled` is the top Java frame in JFR): 3.8 ms on 4 vCPUs, 5.5 ms on 8, and 5–10 ms on the 32 cores of the shared workstation used for the other measurements. Compare off and on within one run only. The full summary and raw rows are in `demo/reference-results/vm-20260926-*`.

To repeat these measurements, see [`vm/README.md`](../vm/README.md).

To run it yourself: start without the agent (`make up-baseline`), run `demo/run_overhead.sh results-overhead agent-off`, restart with the agent (`make up`), run the same script with `agent-on`, then `python3 demo/summarize_overhead.py results-overhead`. It needs `bpftrace` on the PATH (or `BPFTRACE=...`) for the ioctl count.
