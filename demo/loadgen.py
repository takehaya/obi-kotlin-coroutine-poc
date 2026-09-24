#!/usr/bin/env python3
"""Applies HTTP load and prints latency percentiles as one line of JSON.

One new connection per request, like the curl-based scripts, so every request
walks the whole accept / handle / close path the agent instruments.
Usage: loadgen.py URL [--requests N] [--concurrency C] [--warmup W]
       loadgen.py URL --rate R --duration S      (open loop: fixed arrival rate)

In open-loop mode each request is due at a fixed time and its latency is measured from
that due time, so a stalling server shows up as latency instead of slowing the load down.
"""
import argparse
import concurrent.futures
import json
import sys
import threading
import time
import urllib.request

TIMEOUT = 10


def fetch(url):
    """Returns the wall time of one request in seconds, or None if it failed."""
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            r.read()
            if not 200 <= r.status < 300:
                return None
    except Exception:
        return None
    return time.perf_counter() - t0


def run(url, requests, concurrency):
    """Runs `requests` requests over `concurrency` threads.

    Returns (sorted latencies in seconds, error count, wall seconds).
    Thread i takes indices i, i+concurrency, ... so no shared counter is needed.
    """
    slots = [[[], 0] for _ in range(concurrency)]

    def worker(i):
        latencies, errors = slots[i][0], 0
        for _ in range(i, requests, concurrency):
            d = fetch(url)
            if d is None:
                errors += 1
            else:
                latencies.append(d)
        slots[i][1] = errors

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0
    return sorted(d for s in slots for d in s[0]), sum(s[1] for s in slots), wall


def pct(latencies, q):
    """Percentile by sorted-list index; 0 for an empty sample."""
    if not latencies:
        return 0.0
    return latencies[min(len(latencies) - 1, int(q * len(latencies)))] * 1000


def run_open_loop(url, rate, duration, workers):
    """Issues rate req/s for duration seconds. Returns (sorted latencies, errors, sent, wall)."""
    total = int(rate * duration)
    interval = 1.0 / rate
    latencies, errors, late = [], [0], [0]
    lock = threading.Lock()

    def one(due):
        now = time.perf_counter()
        if due > now:
            time.sleep(due - now)
        elif now - due > 0.005:
            with lock:
                late[0] += 1  # the load generator itself fell behind its schedule
        d = fetch(url)
        end = time.perf_counter()
        with lock:
            if d is None:
                errors[0] += 1
            else:
                latencies.append(end - due)

    t0 = time.perf_counter() + 0.1
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for i in range(total):
            pool.submit(one, t0 + i * interval)
    wall = time.perf_counter() - t0
    return sorted(latencies), errors[0], total, wall, late[0]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("url")
    p.add_argument("--requests", type=int, default=2000)
    p.add_argument("--concurrency", type=int, default=16)
    p.add_argument("--warmup", type=int, default=200)
    p.add_argument("--rate", type=float, help="open loop: requests per second")
    p.add_argument("--duration", type=float, default=20, help="open loop: seconds")
    p.add_argument("--workers", type=int, default=512, help="open loop: max requests in flight")
    a = p.parse_args()

    if a.rate:
        latencies, errors, sent, wall, late = run_open_loop(a.url, a.rate, a.duration, a.workers)
        print(json.dumps({
            "mode": "open",
            "target_rps": a.rate,
            "requests": sent,
            "errors": errors,
            "p50_ms": round(pct(latencies, 0.50), 3),
            "p95_ms": round(pct(latencies, 0.95), 3),
            "p99_ms": round(pct(latencies, 0.99), 3),
            "mean_ms": round(sum(latencies) / len(latencies) * 1000, 3) if latencies else 0.0,
            "rps": round((sent - errors) / wall, 1) if wall else 0.0,
            "late_starts": late,
        }))
        return 1 if errors > sent * 0.01 else 0

    run(a.url, a.warmup, a.concurrency)  # discarded: JIT warm-up, pool fill
    latencies, errors, wall = run(a.url, a.requests, a.concurrency)

    print(json.dumps({
        "requests": a.requests,
        "errors": errors,
        "concurrency": a.concurrency,
        "p50_ms": round(pct(latencies, 0.50), 3),
        "p95_ms": round(pct(latencies, 0.95), 3),
        "p99_ms": round(pct(latencies, 0.99), 3),
        "mean_ms": round(sum(latencies) / len(latencies) * 1000, 3) if latencies else 0.0,
        "rps": round(a.requests / wall, 1) if wall else 0.0,
    }))
    # A run that lost more than 1% of its requests is not a latency measurement.
    return 1 if errors > a.requests * 0.01 else 0


if __name__ == "__main__":
    sys.exit(main())
