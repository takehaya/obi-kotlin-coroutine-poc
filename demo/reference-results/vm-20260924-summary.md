# Results: results-vm

- git: 9945050 0 dirty files
- kernel: 6.8.0-139-generic
- cpus: 8, pin=1 load=6-7
- load average at start: 2.64 1.15 0.44 1/221 4269
- config: ROUNDS=5 REQUESTS=4000 CONCURRENCY=16 WARMUP=300 RATES="300 600 900 1200 1500" DURATION=20

## Correctness (agent on)

| setup | sequential | concurrent |
|---|---|---|
| cio | direct 10/10, hop 10/10, parallel 10/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |
| epoll | direct 10/10, hop 10/10, parallel 10/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |
| nio | direct 10/10, hop 10/10, parallel 10/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |

## Closed-loop overhead, median (min-max) over rounds

| endpoint | label | rounds | p50 ms | p99 ms | req/s | CPU ms / request | ioctl / request |
|---|---|---|---|---|---|---|---|
| direct | agent-off | 5 | 24.16 (23.68-24.38) | 41.97 (37.31-44.89) | 602.60 (593.10-618.00) | 3.82 (3.78-3.90) | 7.04 (7.04-7.05) |
| direct | agent-on | 5 | 24.61 (24.45-25.30) | 49.98 (42.47-51.71) | 588.60 (570.70-591.60) | 4.15 (4.04-4.33) | 77.15 (75.77-78.17) |
| hop | agent-off | 5 | 55.64 (55.16-55.82) | 77.08 (71.39-79.99) | 277.30 (274.80-280.10) | 5.81 (5.36-5.85) | 7.01 (7.01-7.01) |
| hop | agent-on | 5 | 56.04 (55.67-56.43) | 113.26 (91.33-163.24) | 269.90 (266.20-270.90) | 6.41 (6.02-6.64) | 84.72 (84.35-85.64) |

## Open-loop /direct

| target req/s | label | achieved req/s | errors | p50 ms | p99 ms | CPU ms / request | late starts |
|---|---|---|---|---|---|---|---|
| 300 | agent-off | 299.7 | 0 | 24.157 | 45.541 | 5.545 | 0 |
| 300 | agent-on | 299.7 | 0 | 24.191 | 78.043 | 5.542 | 0 |
| 600 | agent-off | 599.3 | 0 | 22.62 | 247.006 | 2.532 | 0 |
| 600 | agent-on | 599.4 | 0 | 22.707 | 142.462 | 2.681 | 0 |
| 900 | agent-off | 652.0 | 4957 | 23.752 | 2370.013 | 3.78 | 3596 |
| 900 | agent-on | 625.5 | 5484 | 24.101 | 2113.531 | 4.228 | 3204 |
| 1200 | agent-off | 415.0 | 15694 | 21.597 | 1971.408 | 7.392 | 6165 |
| 1200 | agent-on | 390.1 | 16191 | 42.646 | 2130.95 | 8.233 | 4379 |
| 1500 | agent-off | 672.9 | 15713 | 1406.136 | 2178.648 | 5.18 | 29488 |
| 1500 | agent-on | 786.0 | 12949 | 1721.311 | 2418.699 | 4.447 | 29488 |

- WARNING: over 1% of requests started late at agent-off 900, agent-off 1200, agent-off 1500, agent-on 900, agent-on 1200, agent-on 1500. Either the load generator ran out of CPU, or a stalled server filled all its in-flight slots; check the load generator's CPU before reading these rows as server capacity

- highest rate with <1% errors and p99 < 200 ms, agent-off: 300 req/s
- highest rate with <1% errors and p99 < 200 ms, agent-on: 600 req/s
