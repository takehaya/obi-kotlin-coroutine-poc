#!/usr/bin/env python3
"""Compares the agent-off and agent-on rows of overhead.tsv as a Markdown table.

One row per endpoint. Cells that were never measured print as "-", so a
half-finished run still summarizes.
Usage: summarize_overhead.py <results-dir>
"""
import csv
import sys
from pathlib import Path

OFF, ON = "agent-off", "agent-on"
R = Path(__file__).parent / (sys.argv[1] if len(sys.argv) > 1 else "results-overhead")

rows = {}
endpoints = []
with (R / "overhead.tsv").open() as f:
    for r in csv.DictReader(f, delimiter="\t"):
        rows[(r["endpoint"], r["label"])] = r   # a repeated run wins
        if r["endpoint"] not in endpoints:
            endpoints.append(r["endpoint"])


def cell(ep, label, key):
    r = rows.get((ep, label))
    return r[key] if r else "-"


def delta(ep, key):
    """agent-on relative to agent-off, in percent."""
    off, on = rows.get((ep, OFF)), rows.get((ep, ON))
    if not off or not on:
        return "-"
    try:
        a, b = float(off[key]), float(on[key])
    except ValueError:
        return "-"
    return f"{(b - a) / a * 100:+.1f}%" if a else "-"


def ioctl_per_req(ep):
    r = rows.get((ep, ON))
    try:
        return f"{int(r['ioctl']) / int(r['requests']):.2f}"
    except (TypeError, ValueError, ZeroDivisionError):   # no row, or ioctl=NA
        return "-"


cols = ["endpoint", "p50 off", "p50 on", "p95 off", "p95 on", "p99 off", "p99 on",
        "p99 delta", "rps off", "rps on", "cpu off", "cpu on",
        "ioctl off", "ioctl on", "ioctl/req"]
print("| " + " | ".join(cols) + " |")
print("|" + "|".join(["---"] * len(cols)) + "|")
for ep in endpoints:
    vals = [ep]
    for key in ("p50_ms", "p95_ms", "p99_ms"):
        vals += [cell(ep, OFF, key), cell(ep, ON, key)]
    vals.append(delta(ep, "p99_ms"))
    for key in ("rps", "cpu_cores", "ioctl"):
        vals += [cell(ep, OFF, key), cell(ep, ON, key)]
    vals.append(ioctl_per_req(ep))
    print("| " + " | ".join(vals) + " |")

missing = [lb for lb in (OFF, ON) if not any((ep, lb) in rows for ep in endpoints)]
if missing:
    print("\nlatencies are in ms, cpu in cores; missing label(s): " + ", ".join(missing))
else:
    print("\nlatencies are in ms, cpu in cores.")
