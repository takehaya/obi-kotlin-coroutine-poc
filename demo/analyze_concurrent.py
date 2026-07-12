#!/usr/bin/env python3
"""Summarizes connection rates and mis-parenting signals under concurrent load.

Anomaly checks:
  A) a trace holds 2+ frontend server spans (requests merged: definite mis-correlation)
  B) /direct or /hop trace holds more client spans than expected (absorbed another
     request's client span)
  C) a client span starts outside its co-located server span's interval
     [start-5ms, end+5ms] (timing inconsistency)
"""
import json
import sys
from pathlib import Path

R = Path(__file__).parent / (sys.argv[1] if len(sys.argv) > 1 else "results-concurrent")
EXPECTED_CLIENTS = {"direct": 1, "hop": 1, "parallel": 2}

windows = []
for line in (R / "windows.tsv").read_text().splitlines():
    name, s, e = line.split("\t")
    windows.append((name, int(s), int(e)))

traces = {}
for svc in ["frontend", "backend"]:
    p = R / f"jaeger_{svc}.json"
    if p.exists():
        for t in json.loads(p.read_text())["data"]:
            traces.setdefault(t["traceID"], t)

def spans_of(trace):
    procs = {pid: pr["serviceName"] for pid, pr in trace["processes"].items()}
    out = []
    for s in trace["spans"]:
        kind = next((tag["value"] for tag in s.get("tags", []) if tag["key"] == "span.kind"), "?")
        out.append((procs.get(s["processID"], "?"), kind, s["startTime"], s["startTime"] + s["duration"]))
    return out

for name, ws, we in windows:
    total = {"traces": 0, "full": 0, "server_only": 0, "client_orphan": 0, "other": 0}
    anomalies = {"A_multi_server": 0, "B_extra_clients": 0, "C_timing": 0}
    servers_seen = 0
    for t in traces.values():
        start = min(s["startTime"] for s in t["spans"])
        if not (ws - 2_000_000 <= start <= we + 2_000_000):
            continue
        sp = spans_of(t)
        n_srv = sum(1 for svc, k, *_ in sp if svc == "frontend" and k == "server")
        n_cli = sum(1 for svc, k, *_ in sp if svc == "frontend" and k == "client")
        n_back = sum(1 for svc, k, *_ in sp if svc == "backend" and k == "server")
        servers_seen += n_srv
        total["traces"] += 1
        if n_srv >= 2:
            anomalies["A_multi_server"] += 1
        if n_srv == 1 and n_cli > EXPECTED_CLIENTS.get(name, 99):
            anomalies["B_extra_clients"] += 1
        if n_srv == 1 and n_cli >= 1:
            srv = next((s, e) for svc, k, s, e in sp if svc == "frontend" and k == "server")
            for svc, k, s, e in sp:
                if svc == "frontend" and k == "client":
                    if not (srv[0] - 5000 <= s <= srv[1] + 5000):
                        anomalies["C_timing"] += 1
                        break
        if n_srv == 1 and n_cli >= 1 and n_back >= 1:
            total["full"] += 1
        elif n_srv == 1 and n_cli == 0:
            total["server_only"] += 1
        elif n_srv == 0 and n_cli >= 1:
            total["client_orphan"] += 1
        else:
            total["other"] += 1
    print(f"===== {name} =====")
    print(f"  traces in window: {total['traces']} (total server spans: {servers_seen})")
    print(f"  fully connected (server+client+backend): {total['full']}")
    print(f"  server orphans: {total['server_only']}, client orphans: {total['client_orphan']}, other: {total['other']}")
    print(f"  anomalies: multi_server={anomalies['A_multi_server']}, "
          f"extra_clients={anomalies['B_extra_clients']}, timing={anomalies['C_timing']}")
