# Measuring on a dedicated VM

The numbers in the top-level README were taken on a workstation that was running other heavy workloads at the same time, so CPU and throughput deltas of a few percent there are noise.
These scripts repeat the measurements on a machine that does nothing else, with the services and the load generator on separate CPUs.

## 1. Get a machine

Any Ubuntu 24.04 VM or cloud instance with a BTF-enabled kernel (5.8+, `/sys/kernel/btf/vmlinux`) works.
8 vCPUs or more lets `measure.sh` pin the frontend, the backend and the load generator to separate CPUs.

To boot one locally under KVM, pinned to host cores that nothing else uses:

```bash
HOST_CPUS=8-15 VCPUS=8 MEM=16G vm/run-qemu.sh     # needs qemu-system-x86_64, cloud-localds, /dev/kvm
ssh -p 2222 ubuntu@localhost
```

## 2. Prepare it

```bash
git clone https://github.com/takehaya/obi-kotlin-coroutine-poc && cd obi-kotlin-coroutine-poc
vm/setup.sh     # Docker, JDK 21, gcc, python3, bpftrace; pulls the images, builds, runs the hook check
```

## 3. Measure

```bash
vm/measure.sh                       # about 30 minutes with the defaults
```

It runs three phases and writes everything to `demo/results-vm-<time>/`, plus a `.tar.gz` of it without the raw Jaeger JSON:

| phase | what | output |
|---|---|---|
| correctness | sequential and concurrent load on Netty NIO, Netty epoll and CIO, agent on | `seq-*/`, `conc-*/` |
| overhead | `demo/run_overhead.sh` (closed loop), agent off and on, `ROUNDS` rounds in alternating order | `overhead/overhead.tsv` |
| open loop | fixed arrival rates on `/direct`, agent off and on; latency counted from each request's due time | `openloop.tsv` |

`summary.md` has the tables (medians with min–max over rounds) and `env.txt` records the kernel, CPUs, pinning and what else was running when the run started.

Knobs, all environment variables: `ROUNDS` (5), `REQUESTS` (4000), `CONCURRENCY` (16), `WARMUP` (300), `RATES` ("300 600 900 1200 1500"), `DURATION` (20 s per rate), `STEP_GAP` (60 s between rates), `PIN` (`auto`: on with 8+ CPUs), `SKIP` (e.g. `SKIP=correctness,overhead` for the sweep alone).

Every request opens a new connection, and closed connections hold their source port in TIME_WAIT for 60 s, so the default 28k-port range caps new connections to one destination near 470/s. `measure.sh` therefore widens `net.ipv4.ip_local_port_range` to 1024–65535 and sets `net.ipv4.tcp_tw_reuse=1`, on the machine (restored on exit) and in the frontend container, and waits `STEP_GAP` between rates. Do not run it on a host whose services bind ports in that range. The open-loop load generator runs in a container on the compose network (`python:3.12-slim`), one process per load-generator CPU, and calls the frontend by IP, so it bypasses Docker's userland proxy on the published port.

Each open-loop row also records SYN retransmissions seen by the load generator, listen-queue overflows in the frontend's network namespace, and the VM's steal time. Steal above 2% means the host ran something else on the VM's CPUs during that step; `summary.md` marks those rows.

The open-loop sweep needs a load generator faster than the server. Check the `late starts` column: if requests started late while errors stayed at zero, the load generator was the limit. 16 vCPUs give it four processes.

Compare agent off and agent on within one run only; a VM adds its own virtualization overhead, so its absolute numbers are not comparable with bare-metal ones.
