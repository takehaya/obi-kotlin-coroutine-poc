# Results: results-vm

- git: f149f89 0 dirty files
- kernel: 6.8.0-139-generic
- cpus: 16, pin=1 load=12-15 (4 loadgen processes)
- load average at start: 4.52 1.57 0.57 2/309 4744
- config: ROUNDS=5 REQUESTS=4000 CONCURRENCY=16 WARMUP=300 RATES="600 900 1200 1500 2000 2500 3000" DURATION=20

## Correctness (agent on)

| setup | sequential | concurrent |
|---|---|---|
| cio | direct 10/10, hop 10/10, parallel 10/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |
| epoll | direct 10/10, hop 10/10, parallel 9/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |
| nio | direct 10/10, hop 10/10, parallel 10/10, java 10/10 | direct 200/200 (mis-parented 0), hop 200/200 (mis-parented 0), parallel 100/100 (mis-parented 0) |

## Closed-loop overhead, median (min-max) over rounds

| endpoint | label | rounds | p50 ms | p99 ms | req/s | CPU ms / request | ioctl / request |
|---|---|---|---|---|---|---|---|
| direct | agent-off | 5 | 24.18 (24.00-24.47) | 38.92 (37.21-40.97) | 604.90 (598.00-610.40) | 5.54 (5.40-5.66) | 7.00 (7.00-7.01) |
| direct | agent-on | 5 | 24.62 (24.35-24.73) | 40.99 (37.22-42.33) | 595.50 (586.70-601.20) | 5.74 (5.67-5.82) | 77.15 (77.10-77.28) |
| hop | agent-off | 5 | 55.87 (55.79-56.00) | 68.69 (67.66-74.79) | 275.00 (274.30-276.20) | 7.52 (7.46-7.87) | 7.00 (7.00-7.00) |
| hop | agent-on | 5 | 56.03 (55.88-56.10) | 78.74 (76.93-80.44) | 273.90 (273.20-275.50) | 8.39 (8.05-8.87) | 84.01 (83.61-84.53) |

## Open-loop /direct

| target req/s | label | achieved req/s | errors | p50 ms | p99 ms | CPU ms / request | late starts | SYN resends | listen overflows | steal % |
|---|---|---|---|---|---|---|---|---|---|---|
| 600 | agent-off | 598.8 | 0 | 23.295 | 95.907 | 5.105 | 0 | 0 | 0 | 0.04 |
| 600 | agent-on | 598.9 | 0 | 23.138 | 115.828 | 5.126 | 0 | 0 | 0 | 0.02 |
| 900 | agent-off | 898.3 | 0 | 22.615 | 33.148 | 2.488 | 0 | 0 | 0 | 0.03 |
| 900 | agent-on | 898.3 | 0 | 22.571 | 73.815 | 2.598 | 0 | 0 | 0 | 0.02 |
| 1200 | agent-off | 1197.9 | 0 | 21.785 | 31.351 | 1.554 | 0 | 0 | 0 | 0.01 |
| 1200 | agent-on | 1197.7 | 0 | 21.81 | 34.946 | 1.749 | 0 | 0 | 0 | 0.01 |
| 1500 | agent-off | 1496.8 | 0 | 21.912 | 27.948 | 1.499 | 0 | 0 | 0 | 0.01 |
| 1500 | agent-on | 1496.2 | 0 | 21.921 | 90.866 | 1.828 | 16 | 0 | 0 | 0.02 |
| 2000 | agent-off | 1996.5 | 0 | 21.375 | 42.224 | 1.617 | 40 | 0 | 0 | 0.01 |
| 2000 | agent-on | 1996.9 | 0 | 21.336 | 32.208 | 1.728 | 0 | 0 | 0 | 0.02 |
| 2500 | agent-off | 2493.2 | 0 | 21.406 | 810.329 | 1.547 | 2960 | 0 | 0 | 0.02 |
| 2500 | agent-on | 2493.8 | 0 | 21.447 | 837.613 | 1.731 | 2790 | 0 | 0 | 0.02 |
| 3000 | agent-off | 2993.5 | 0 | 21.399 | 731.1 | 1.54 | 2656 | 0 | 0 | 0.02 |
| 3000 | agent-on | 2993.8 | 0 | 21.568 | 475.939 | 1.671 | 1983 | 0 | 0 | 0.02 |

- WARNING: over 1% of requests started late at agent-off 2500, agent-off 3000, agent-on 2500, agent-on 3000. Either the load generator ran out of CPU, or a stalled server filled all its in-flight slots; check the load generator's CPU before reading these rows as server capacity

- highest rate with <1% errors and p99 < 200 ms, agent-off: 2000 req/s
- highest rate with <1% errors and p99 < 200 ms, agent-on: 2000 req/s
