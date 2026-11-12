# How it works

## The problem

OBI associates a service's incoming request with its outgoing calls through thread identity (plus clone-chain lookups, `Executor` instrumentation in its embedded Java agent, and virtual-thread mount tracking since v0.10.0). Kotlin coroutines fall through all of these paths: `kotlinx.coroutines` dispatches work through its own scheduler, and a request's identity lives in heap `Continuation` objects rather than in any thread.

The result, measured with the demo in this repo (OBI v0.10.0, Ktor 3.2.2, plaintext HTTP; re-measured with OBI v1.0.0 on 2026-11-12 with the same outcome, see [`demo/reference-results/obi-v1.0.0/`](../demo/reference-results/obi-v1.0.0/)):

- Every request through the coroutine-based frontend splits into two traces: an orphaned server span, and a separate trace holding the client span + the downstream service. 30/30 requests split across three endpoint shapes — including `/direct`, which contains no user-written suspension at all.
- A thread-per-request plain-Java control service on the same topology connects 10/10.

## The agent

A standalone `-javaagent` (ByteBuddy + a ~40-line JNI shim) makes coroutines masquerade as virtual threads, using OBI's **existing** ioctl control channel (`k_ioctl_java_vt_mount` / `unmount`). OBI itself is unmodified; the ioctl kprobe accepts the ops because the process is already an instrumented target.

- **Lineage id**: a sequential number assigned to each incoming connection object.
- **Scopes** (where an id enters a thread): Netty `NioByteUnsafe.read` / `EpollStreamUnsafe.epollInReady` (bracket the recv syscall, keying the server-span insert; the NIO and epoll transports are covered, io_uring and KQueue are not), inbound handlers' `channelRead` (the cross-event-loop handoff is a hidden-class lambda and cannot be instrumented, so the receiving side recovers the id from `ctx.channel()`), ktor-network `NIOSocketImpl.attachFor*Impl` with preserve-or-seed semantics, and the CIO server's `startServerConnectionPipeline`, which launches a connection's request pipeline from the accept loop and gets the id its socket was attached under.
- **Carry**: constructors of kotlinx/ktor `Runnable`s stamp the current id onto the task, once. A coroutine belongs to the request that launched it; re-stamping on dispatch would hand it the id of whichever request's thread happened to wake it.
- **Apply**: task `run()` entry mounts the id, exit restores the previous state — no state ever lingers on a thread.

See [results.md](results.md) for what it achieves and [limitations.md](limitations.md) for what it cannot do.
