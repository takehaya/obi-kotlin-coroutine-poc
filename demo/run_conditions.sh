#!/bin/bash
# Runs the four conditions sequentially and records their time windows.
# Usage: ./run_conditions.sh <results-dir>
set -eu
cd "$(dirname "$0")"
OUT=${1:-results}
mkdir -p "$OUT"
: > "$OUT/windows.tsv"

run() {
    local name=$1 url=$2
    local start end
    start=$(date +%s%6N)   # microseconds
    for i in $(seq 1 10); do
        curl -s -m 5 "$url" > /dev/null
        sleep 0.3
    done
    end=$(date +%s%6N)
    echo -e "$name\t$start\t$end" >> "$OUT/windows.tsv"
    echo "condition $name done"
    sleep 3
}

run direct   http://localhost:8080/direct
run hop      http://localhost:8080/hop
run parallel http://localhost:8080/parallel
run java     http://localhost:8082/call

echo "waiting for OBI batch flush (20s)..."
sleep 20

for svc in frontend backend jfront; do
    curl -s "http://localhost:16686/api/traces?service=$svc&limit=500&lookback=1h" > "$OUT/jaeger_$svc.json"
    echo "$svc: $(python3 -c "import json,sys; print(len(json.load(open('$OUT/jaeger_$svc.json'))['data']))") traces"
done
