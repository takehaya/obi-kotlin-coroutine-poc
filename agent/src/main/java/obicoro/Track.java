package obicoro;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Carries a request "lineage id" along the coroutine task graph and brackets task execution with
 * OBI's virtual-thread mount/unmount ioctl ops (4/5).
 *
 * <p>While an id is mounted, OBI's eBPF side substitutes the trace-key tid of that thread with
 * {@code 0x80000000 | id}. Both the server-span insert (on the receiving recv syscall) and the
 * parent lookup (on the outgoing send syscall) use that key, so if the receive and the send carry
 * the same logical id, the trace connects; concurrent requests carry different ids and no longer
 * collide on a shared event-loop tid (which would otherwise invalidate the server span through
 * OBI's conflict branch).
 *
 * <p>Id policy is "preserve-or-seed": when entering a connection scope, keep the current lineage id
 * if one exists (client sockets created inside a request), otherwise seed from the connection
 * object's identity hash (server-side receives).
 *
 * <p>This class is appended to the bootstrap class loader and referenced from instrumented code.
 * It must not depend on anything outside the JDK.
 */
public final class Track {
  private Track() {}

  private static volatile boolean debug;
  private static final AtomicInteger debugBudget =
      new AtomicInteger(Integer.getInteger("obicoro.debugBudget", 300));

  /**
   * task -> lineage id, fixed at construction and read by every run().
   *
   * <p>Weak keys: an entry lives exactly as long as its task, including tasks that never reach
   * run() (a DispatchedContinuation resumed through resumeUndispatched, unconfined paths).
   *
   * <p>Precondition: WeakHashMap looks keys up by their own equals/hashCode, so carrier tasks must
   * not override them with value semantics. Verified for kotlinx-coroutines 1.10.2 and Ktor 3.2.2:
   * no Runnable in those jars declares equals(Object) or hashCode().
   */
  // ponytail: global lock on every stamp/run; shard or use a ConcurrentHashMap<WeakKey,..> with a
  // ReferenceQueue if it shows up in profiles
  private static final Map<Object, Long> pendingId =
      Collections.synchronizedMap(new WeakHashMap<Object, Long>());

  /** Current lineage id per thread. 0 = none. */
  private static final ThreadLocal<long[]> activeId = ThreadLocal.withInitial(() -> new long[1]);

  /** Per-class cache of the this$0 (inner-class outer reference) field. */
  private static final ClassValue<Field> OUTER =
      new ClassValue<Field>() {
        @Override
        protected Field computeValue(Class<?> type) {
          for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
              Field f = c.getDeclaredField("this$0");
              f.setAccessible(true);
              return f;
            } catch (NoSuchFieldException ignored) {
              // keep walking up
            }
          }
          return null;
        }
      };

  public static void init(String nativePath, boolean dbg) {
    System.load(nativePath);
    debug = dbg;
    log("initialized, tid=" + Native.gettid0());
  }

  /** Derives a 31-bit logical id from a connection object (the BPF side uses the low 31 bits). */
  private static long channelId(Object o) {
    int h = System.identityHashCode(o) & 0x7FFFFFFF;
    return h == 0 ? 1 : h;
  }

  // ---- carrying the id along the task graph ----

  /**
   * Constructor exit of kotlinx/ktor Runnables: tasks born inside a lineage inherit its id, for
   * good. A coroutine belongs to the request that launched it, so the id is never refreshed
   * later: a dispatch runs on whatever thread wakes the coroutine, which under concurrency is
   * often busy with another request.
   */
  public static void created(Object task) {
    if (task instanceof Thread) {
      return; // worker threads themselves (CoroutineScheduler$Worker etc.) are not carriers
    }
    stamp(task);
  }

  private static void stamp(Object task) {
    long id = activeId.get()[0];
    if (id == 0) {
      return; // tasks born outside any lineage are not carried (no mount = stock behavior)
    }
    pendingId.put(task, id);
    if (debug && debugBudget.getAndDecrement() > 0) {
      log("stamp id=" + id + " tid=" + Native.gettid0() + " task=" + task.getClass().getName());
    }
  }

  /** Runnable.run entry. Returns the previous id (restored on exit). */
  public static long runEnter(Object task) {
    long prev = activeId.get()[0];
    if (task instanceof Thread) {
      return prev;
    }
    Long id = pendingId.get(task);
    if (id != null && id != 0L) {
      setActive(id, prev, task.getClass().getName());
    }
    return prev;
  }

  /** Runnable.run exit. */
  public static void runExit(long prev) {
    restore(prev);
  }

  // ---- connection scopes (Netty reads / handler invocations, ktor-network socket attach) ----

  /** Connection scope entry with preserve-or-seed. Returns the previous id. */
  public static long scopeEnter(Object channelLike) {
    long prev = activeId.get()[0];
    long id = prev != 0 ? prev : channelId(channelLike);
    setActive(id, prev, "scope:" + channelLike.getClass().getSimpleName());
    return prev;
  }

  /**
   * Enters a scope from an inner class (Netty's NioByteUnsafe) by following this$0 to the channel.
   * A socket read IS this connection's work, so the channel id is authoritative: no preserve here.
   */
  public static long scopeEnterOuter(Object inner) {
    try {
      Field f = OUTER.get(inner.getClass());
      Object ch = f != null ? f.get(inner) : null;
      Object owner = ch != null ? ch : inner;
      long prev = activeId.get()[0];
      setActive(channelId(owner), prev, "read:" + owner.getClass().getSimpleName());
      return prev;
    } catch (Throwable t) {
      return activeId.get()[0];
    }
  }

  /** Per-class cache of the channel() method (for Netty's ChannelHandlerContext). */
  private static final ClassValue<java.lang.reflect.Method> CHANNEL_OF =
      new ClassValue<java.lang.reflect.Method>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> type) {
          try {
            java.lang.reflect.Method m = type.getMethod("channel");
            m.setAccessible(true);
            return m;
          } catch (Throwable t) {
            return null;
          }
        }
      };

  /**
   * Netty inbound handler entry (channelRead). Recovers the connection id from ctx.channel().
   * This catches the receiving side of cross-event-loop handoffs, which happen through
   * hidden-class lambdas that cannot be instrumented.
   */
  public static long scopeEnterCtx(Object ctx) {
    try {
      java.lang.reflect.Method m = CHANNEL_OF.get(ctx.getClass());
      Object ch = m != null ? m.invoke(ctx) : null;
      if (ch == null) {
        return activeId.get()[0];
      }
      // Always the channel id, no preserve: handler invocation IS this connection's work.
      long prev = activeId.get()[0];
      setActive(channelId(ch), prev, "ctx:" + ch.getClass().getSimpleName());
      return prev;
    } catch (Throwable t) {
      return activeId.get()[0];
    }
  }

  /** Connection scope exit. */
  public static void scopeExit(long prev) {
    restore(prev);
  }

  // ---- mount state management ----

  private static void setActive(long id, long prev, String what) {
    activeId.get()[0] = id;
    if (id != prev) {
      Native.mount0(id);
      if (debug && debugBudget.getAndDecrement() > 0) {
        log("mount id=" + id + " tid=" + Native.gettid0() + " at=" + what);
      }
    }
  }

  private static void restore(long prev) {
    long[] slot = activeId.get();
    long cur = slot[0];
    if (cur != prev) {
      slot[0] = prev;
      if (prev != 0) {
        Native.mount0(prev);
      } else {
        Native.unmount0();
      }
    }
  }

  private static void log(String msg) {
    System.err.println("[obicoro] " + msg);
  }
}
