#!/usr/bin/env python3
"""Summarizes a vm/measure.sh results directory as Markdown.

Usage: summarize.py <results-dir>
"""
import csv
import re
import statistics
import sys
from pathlib import Path

R = Path(sys.argv[1])
STEAL_LIMIT = 2.0  # % of CPU time the hypervisor gave to others during a step


def fmt(values):
    """median (min-max) of a list of numbers."""
    if not values:
        return "-"
    m = statistics.median(values)
    return f"{m:.2f} ({min(values):.2f}-{max(values):.2f})" if len(values) > 1 else f"{m:.2f}"


def sequential(path):
    """condition -> 'connected/total' from analyze_jaeger.py output."""
    out, cond = {}, None
    for line in path.read_text().splitlines():
        m = re.match(r"===== condition: (\S+) =====", line)
        if m:
            cond = m.group(1)
            out[cond] = [0, 0]
            continue
        m = re.match(r"\s*(\d+) traces: (.*)", line)
        if m and cond:
            n, labels = int(m.group(1)), m.group(2)
            server = "/server:" in labels and ("frontend/server" in labels or "jfront/server" in labels)
            full = server and "/client:" in labels and "backend/server" in labels
            if server:
                out[cond][1] += n
            if full:
                out[cond][0] += n
    return {k: f"{a}/{b}" for k, (a, b) in out.items()}


def concurrent(path):
    """condition -> 'connected, mis-parented' from analyze_concurrent.py output."""
    out, cond, servers = {}, None, {}
    for line in path.read_text().splitlines():
        m = re.match(r"===== (\S+) =====", line)
        if m:
            cond = m.group(1)
        m = re.search(r"total server spans: (\d+)", line)
        if m and cond:
            servers[cond] = m.group(1)
        m = re.search(r"fully connected \(server\+client\+backend\): (\d+)", line)
        if m and cond:
            out[cond] = f"{m.group(1)}/{servers.get(cond, '?')}"
        m = re.search(r"extra_clients=(\d+)", line)
        if m and cond:
            out[cond] += f" (mis-parented {m.group(1)})"
    return out


print(f"# Results: {R.name}\n")
env = R / "env.txt"
if env.exists():
    keep = [l for l in env.read_text().splitlines() if l.split(":")[0] in ("kernel", "cpus", "load average at start", "config", "git")]
    print("\n".join(f"- {l}" for l in keep) + "\n")

seqs = sorted(R.glob("seq-*/analysis.txt"))
if seqs:
    print("## Correctness (agent on)\n")
    print("| setup | sequential | concurrent |\n|---|---|---|")
    for s in seqs:
        v = s.parent.name[len("seq-"):]
        sq = sequential(s)
        c = R / f"conc-{v}" / "analysis.txt"
        cc = concurrent(c) if c.exists() else {}
        print(f"| {v} | {', '.join(f'{k} {x}' for k, x in sq.items())} | {', '.join(f'{k} {x}' for k, x in cc.items())} |")
    print()

ov = R / "overhead" / "overhead.tsv"
if ov.exists():
    rows = list(csv.DictReader(ov.open(), delimiter="\t"))
    print("## Closed-loop overhead, median (min-max) over rounds\n")
    print("| endpoint | label | rounds | p50 ms | p99 ms | req/s | CPU ms / request | ioctl / request |")
    print("|---|---|---|---|---|---|---|---|")
    for ep in sorted({r["endpoint"] for r in rows}):
        for label in ("agent-off", "agent-on"):
            rs = [r for r in rows if r["endpoint"] == ep and r["label"] == label]
            if not rs:
                continue
            num = lambda k: [float(r[k]) for r in rs if r[k] not in ("", "NA")]
            cpu = [float(r["cpu_cores"]) / float(r["rps"]) * 1000 for r in rs if float(r["rps"])]
            ioc = [int(r["ioctl"]) / int(r["requests"]) for r in rs if r["ioctl"] not in ("", "NA")]
            print(f"| {ep} | {label} | {len(rs)} | {fmt(num('p50_ms'))} | {fmt(num('p99_ms'))} | {fmt(num('rps'))} | {fmt(cpu)} | {fmt(ioc)} |")
    print()

ol = R / "openloop.tsv"
if ol.exists():
    rows = list(csv.DictReader(ol.open(), delimiter="\t"))
    print("## Open-loop /direct\n")
    print("| target req/s | label | achieved req/s | errors | p50 ms | p99 ms | CPU ms / request | late starts | SYN resends | listen overflows | steal % |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda r: (float(r["target_rps"]), r["label"])):
        noisy = " (host contention)" if float(r.get("steal_pct") or 0) > STEAL_LIMIT else ""
        print(f"| {r['target_rps']} | {r['label']} | {r['rps']} | {r['errors']} | {r['p50_ms']} | {r['p99_ms']} | {r['cpu_ms_per_req']} | {r.get('late_starts', '-')} | {r.get('syn_retrans', '-')} | {r.get('listen_overflows', '-')} | {r.get('steal_pct', '-')}{noisy} |")
    print()
    late = [r for r in rows if int(r.get("late_starts") or 0) > 0.01 * int(r["requests"])]
    if late:
        print("- WARNING: over 1% of requests started late at "
              + ", ".join(f"{r['label']} {r['target_rps']}" for r in late)
              + ". Either the load generator ran out of CPU, or a stalled server filled all its"
              " in-flight slots; check the load generator's CPU before reading these rows as"
              " server capacity\n")
    noisy = [r for r in rows if float(r.get("steal_pct") or 0) > STEAL_LIMIT]
    if noisy:
        print(f"- WARNING: steal above {STEAL_LIMIT}% (the host ran something else on our CPUs) at "
              + ", ".join(f"{r['label']} {r['target_rps']}" for r in noisy) + "; do not compare those rows\n")
    for label in ("agent-off", "agent-on"):
        ok = [float(r["target_rps"]) for r in rows if r["label"] == label
              and int(r["errors"]) <= 0.01 * int(r["requests"]) and float(r["p99_ms"]) < 200]
        print(f"- highest rate with <1% errors and p99 < 200 ms, {label}: {max(ok):g} req/s" if ok else f"- {label}: no rate met the bar")
