#!/bin/bash
# Applies concurrent load per endpoint and records the time windows.
# Usage: ./run_concurrent.sh <results-dir>
set -eu
cd "$(dirname "$0")"
OUT=${1:-results-concurrent}
mkdir -p "$OUT"
: > "$OUT/windows.tsv"

run() {
    local name=$1 url=$2 total=$3 conc=$4
    local start end
    start=$(date +%s%6N)
    seq 1 "$total" | xargs -P "$conc" -I{} curl -s -m 10 "$url" -o /dev/null
    end=$(date +%s%6N)
    echo -e "$name\t$start\t$end" >> "$OUT/windows.tsv"
    echo "condition $name done ($total reqs, concurrency $conc)"
    sleep 3
}

run direct   http://localhost:8080/direct   200 16
run hop      http://localhost:8080/hop      200 16
run parallel http://localhost:8080/parallel 100 8

echo "waiting for OBI batch flush (45s)..."
sleep 45

for svc in frontend backend; do
    curl -s "http://localhost:16686/api/traces?service=$svc&limit=2000&lookback=1h" > "$OUT/jaeger_$svc.json"
done
echo "saved to $OUT/"
