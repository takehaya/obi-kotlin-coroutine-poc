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
import collections
import multiprocessing
import urllib.error
import urllib.request

TIMEOUT = 10


def fetch(url):
    """Returns (wall time of one request in seconds or None if it failed, failure kind)."""
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            r.read()
            if not 200 <= r.status < 300:
                return None, f"http {r.status}"
    except urllib.error.HTTPError as e:
        return None, f"http {e.code}"
    except Exception as e:
        reason = getattr(e, "reason", None)
        return None, type(reason if isinstance(reason, BaseException) else e).__name__
    return time.perf_counter() - t0, None


def run(url, requests, concurrency):
    """Runs `requests` requests over `concurrency` threads.

    Returns (sorted latencies in seconds, error count, wall seconds).
    Thread i takes indices i, i+concurrency, ... so no shared counter is needed.
    """
    slots = [[[], 0] for _ in range(concurrency)]

    def worker(i):
        latencies, errors = slots[i][0], 0
        for _ in range(i, requests, concurrency):
            d, _ = fetch(url)
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


def run_open_loop(url, rate, duration, workers, t0, offset=0.0):
    """Issues rate req/s for duration seconds starting at monotonic time t0 + offset.

    Returns (latencies, errors by kind, sent, late starts).
    """
    total = int(rate * duration)
    interval = 1.0 / rate
    latencies, errors, late = [], collections.Counter(), [0]
    lock = threading.Lock()

    def one(due):
        now = time.monotonic()
        if due > now:
            time.sleep(due - now)
        elif now - due > 0.005:
            with lock:
                late[0] += 1  # started late: out of CPU, or all in-flight slots busy
        d, kind = fetch(url)
        end = time.monotonic()
        with lock:
            if d is None:
                errors[kind] += 1
            else:
                latencies.append(end - due)

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for i in range(total):
            pool.submit(one, t0 + offset + i * interval)
    return latencies, errors, total, late[0]


def _open_loop_proc(args):
    return run_open_loop(*args)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("url")
    p.add_argument("--requests", type=int, default=2000)
    p.add_argument("--concurrency", type=int, default=16)
    p.add_argument("--warmup", type=int, default=200)
    p.add_argument("--rate", type=float, help="open loop: requests per second")
    p.add_argument("--duration", type=float, default=20, help="open loop: seconds")
    p.add_argument("--workers", type=int, default=512, help="open loop: max requests in flight")
    p.add_argument("--procs", type=int, default=1,
                   help="open loop: processes sharing the rate (one Python process tops out near one core)")
    a = p.parse_args()

    if a.rate:
        procs = max(1, a.procs)
        t0 = time.monotonic() + 0.5
        # Each process takes rate/procs, its schedule shifted so the arrivals interleave.
        jobs = [(a.url, a.rate / procs, a.duration, max(1, a.workers // procs), t0, i / a.rate)
                for i in range(procs)]
        with multiprocessing.Pool(procs) as pool:
            parts = pool.map(_open_loop_proc, jobs)
        wall = time.monotonic() - t0
        latencies = sorted(x for part in parts for x in part[0])
        kinds = sum((part[1] for part in parts), collections.Counter())
        errors = sum(kinds.values())
        sent = sum(part[2] for part in parts)
        late = sum(part[3] for part in parts)
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
            "procs": procs,
            "error_kinds": dict(kinds.most_common(3)),
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
