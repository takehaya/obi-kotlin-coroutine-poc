#!/bin/bash
# Measures the agent's cost on the frontend for one condition: latency
# percentiles, JVM CPU and the ioctl syscalls the agent adds around task runs.
# Run it once with the stack started without FRONTEND_JAVA_OPTS (agent-off) and
# once with it (agent-on); both rows land in the same overhead.tsv.
# Usage: ./run_overhead.sh <results-dir> <label>   (label: agent-off | agent-on)
# Env: REQUESTS, CONCURRENCY, WARMUP, BPFTRACE
set -eu
cd "$(dirname "$0")"
OUT=${1:?usage: run_overhead.sh <results-dir> <label>}
LABEL=${2:?usage: run_overhead.sh <results-dir> <label>}
REQUESTS=${REQUESTS:-2000}
CONCURRENCY=${CONCURRENCY:-16}
WARMUP=${WARMUP:-200}
BPFTRACE=${BPFTRACE:-bpftrace}
HZ=$(getconf CLK_TCK)

mkdir -p "$OUT"
TSV=$OUT/overhead.tsv
[ -f "$TSV" ] || printf 'label\tendpoint\trequests\tconcurrency\terrors\tp50_ms\tp95_ms\tp99_ms\tmean_ms\trps\tcpu_cores\tioctl\n' > "$TSV"

# utime+stime (fields 14 and 15) of a host pid. The comm field can hold spaces,
# so drop everything through the last ')' and count from the state field.
cpu_ticks() {
    sudo awk '{ sub(/^.*\) /, ""); print $12 + $13 }' "/proc/$1/stat"
}

# bpftrace prints its maps on SIGINT. sudo does not relay signals to the
# command when it runs unattended in the background, so signal the bpftrace
# child itself, falling back to the sudo pid if sudo exec'd it without forking.
# Returns non-zero if nothing was there to signal.
stop_bpftrace() {
    local sudo_pid=$1 child i
    child=$(pgrep -P "$sudo_pid" || true)
    sudo kill -INT ${child:-$sudo_pid} 2>/dev/null || return 1
    for i in $(seq 1 30); do
        [ -d "/proc/$sudo_pid" ] || return 0
        sleep 0.5
    done
    # Still alive after 15s: do not let one stuck probe hang the whole run.
    sudo kill -TERM ${child:-$sudo_pid} 2>/dev/null || true
}

run() {
    local ep=$1
    local pid t0 t1 start_ns end_ns bt_pid bt_out json ioctl cpu row

    # Warm up (JIT, connection pools) before the CPU and ioctl windows open, so
    # neither counts requests that are not in the latency sample.
    python3 loadgen.py "http://localhost:8080/$ep" \
        --requests "$WARMUP" --concurrency "$CONCURRENCY" --warmup 0 > /dev/null || true

    pid=$(sudo docker inspect -f '{{.State.Pid}}' "$(sudo docker compose ps -q frontend)")
    bt_out=$(mktemp)
    # BPFTRACE may be an AppImage plus flags, so it is deliberately word-split.
    sudo $BPFTRACE -e 'tracepoint:syscalls:sys_enter_ioctl /pid == '"$pid"'/ { @ioctl = count(); }' \
        > "$bt_out" 2>&1 &
    bt_pid=$!
    sleep 2   # give bpftrace time to attach before the load starts

    # The CPU window brackets the load only, not the bpftrace attach/detach waits.
    t0=$(cpu_ticks "$pid")
    start_ns=$(date +%s%N)
    json=$(python3 loadgen.py "http://localhost:8080/$ep" \
        --requests "$REQUESTS" --concurrency "$CONCURRENCY" --warmup 0) \
        || echo "warning: loadgen exited non-zero for $ep (over 1% errors)" >&2
    end_ns=$(date +%s%N)
    t1=$(cpu_ticks "$pid")

    if stop_bpftrace "$bt_pid"; then
        wait "$bt_pid" 2>/dev/null || true
    else
        echo "warning: bpftrace was no longer running for $ep" >&2
    fi

    cpu=$(awk -v d="$((t1 - t0))" -v ns="$((end_ns - start_ns))" -v hz="$HZ" \
        'BEGIN { printf("%.3f", ns > 0 ? (d / hz) / (ns / 1000000000) : 0) }')

    ioctl=$(awk '/^@ioctl:/ { v = $2 } END { print v }' "$bt_out")
    if [ -z "$ioctl" ]; then
        echo "warning: no ioctl count for $ep, recording NA. bpftrace said: $(tr '\n' ' ' < "$bt_out" | cut -c1-200)" >&2
        ioctl=NA
    fi
    rm -f "$bt_out"

    if [ -z "$json" ]; then
        echo "error: loadgen produced no output for $ep" >&2
        return 1
    fi
    row=$(printf '%s' "$json" | python3 -c 'import json, sys
d = json.load(sys.stdin)
label, ep, cpu, ioctl = sys.argv[1:]
print("\t".join(str(v) for v in [label, ep, d["requests"], d["concurrency"], d["errors"],
                                 d["p50_ms"], d["p95_ms"], d["p99_ms"], d["mean_ms"],
                                 d["rps"], cpu, ioctl]))' "$LABEL" "$ep" "$cpu" "$ioctl")
    printf '%s\n' "$row" >> "$TSV"
    printf '%s\n' "$row"
}

for ep in direct hop; do
    run "$ep"
done
echo "appended to $TSV"
