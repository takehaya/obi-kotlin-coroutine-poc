#!/usr/bin/env python3
"""Summarizes, per condition window, how spans grouped into traces (connected vs split)."""
import json
import sys
from collections import Counter
from pathlib import Path

R = Path(__file__).parent / (sys.argv[1] if len(sys.argv) > 1 else "results")

# Per-condition time windows (microseconds).
windows = []
for line in (R / "windows.tsv").read_text().splitlines():
    name, s, e = line.split("\t")
    windows.append((name, int(s), int(e)))

# Merge traces from all services by traceID.
traces = {}
for svc in ["frontend", "backend", "jfront"]:
    p = R / f"jaeger_{svc}.json"
    if not p.exists():
        continue
    for t in json.loads(p.read_text())["data"]:
        traces.setdefault(t["traceID"], t)

def span_kind(span):
    for tag in span.get("tags", []):
        if tag["key"] == "span.kind":
            return tag["value"]
    return "?"

def classify(trace):
    """trace -> (composition label, earliest startTime)"""
    procs = {pid: p["serviceName"] for pid, p in trace["processes"].items()}
    parts = set()
    start = min(s["startTime"] for s in trace["spans"])
    for s in trace["spans"]:
        svc = procs.get(s["processID"], "?")
        kind = span_kind(s)
        op = s["operationName"]
        parts.add(f"{svc}/{kind}:{op}")
    return frozenset(parts), start

for name, ws, we in windows:
    comps = Counter()
    for t in traces.values():
        label, start = classify(t)
        # Span startTime is the actual request time, so the window applies almost as-is.
        if ws - 2_000_000 <= start <= we + 2_000_000:
            comps[label] += 1
    print(f"\n===== condition: {name} =====")
    for label, n in comps.most_common():
        print(f"  {n:3d} traces: {sorted(label)}")
