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

# CONDITIONS picks the endpoints, e.g. CONDITIONS="direct shared vt"; "java" is the control.
for name in ${CONDITIONS:-direct hop parallel java}; do
    if [ "$name" = java ]; then run java http://localhost:8082/call; else run "$name" "http://localhost:8080/$name"; fi
done

echo "waiting for OBI batch flush (45s)..."
sleep 45

for svc in frontend backend jfront; do
    curl -s "http://localhost:16686/api/traces?service=$svc&limit=500&lookback=1h" > "$OUT/jaeger_$svc.json"
    echo "$svc: $(python3 -c "import json,sys; print(len(json.load(open('$OUT/jaeger_$svc.json'))['data']))") traces"
done
