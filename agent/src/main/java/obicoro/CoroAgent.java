package obicoro;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Experimental agent that carries a per-request lineage id along the kotlinx.coroutines / Ktor
 * task graph and reports it to OBI through the existing virtual-thread mount/unmount ioctl ops,
 * so that OBI's in-process trace correlation works across coroutine suspension points.
 * OBI itself is left unmodified.
 */
public final class CoroAgent {
  private CoroAgent() {}

  public static void premain(String args, Instrumentation inst) throws Exception {
    // Advice code is inlined into target classes, so the referenced Track / Native classes must
    // be visible from every class loader: append them to the bootstrap search path.
    // Appending the fat jar would double-load ByteBuddy (bootstrap + app loader) and fail with a
    // LinkageError, hence the separate boot jar with just Track / Native.
    String bootJar = System.getProperty("obicoro.bootjar", "/coroagent/coroagent-boot.jar");
    inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar));

    String nativePath = System.getProperty("obicoro.native", "/coroagent/libcoroagent.so");
    boolean dbg = System.getenv("OBICORO_DEBUG") != null;
    Track.init(nativePath, dbg);

    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
        .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
        // Task creation and execution: kotlinx.coroutines / Ktor Runnables.
        // io.netty Runnables are deliberately NOT included: instrumenting event-loop bodies
        // (run() methods that never return) leaves stale mounts on threads and poisons every
        // lineage. Cross-event-loop handoffs are handled by HandlerChannelRead instead.
        // EventLoopImplBase subtypes are excluded for the same reason: DefaultExecutor is a
        // singleton created lazily by whichever lineage first calls delay(), and its run() is the
        // event-loop body of the DefaultExecutor thread, which never returns: that first lineage
        // would stay mounted on that thread forever.
        .type(
            nameStartsWith("kotlinx.coroutines")
                .or(nameStartsWith("io.ktor"))
                .and(isSubTypeOf(Runnable.class))
                .and(not(hasSuperType(named("kotlinx.coroutines.EventLoopImplBase")))))
        .transform(
            (builder, type, cl, module, pd) ->
                builder
                    .visit(Advice.to(TaskCreated.class).on(isConstructor()))
                    .visit(
                        Advice.to(TaskRun.class)
                            .on(named("run").and(takesArguments(0)).and(not(isAbstract())))))
        // Dispatch: CoroutineDispatcher (including Ktor subclasses such as NettyDispatcher).
        .type(hasSuperType(named("kotlinx.coroutines.CoroutineDispatcher")))
        .transform(
            (builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(Dispatched.class)
                        .on(
                            named("dispatch")
                                .or(named("dispatchYield"))
                                .and(takesArguments(2))
                                .and(not(isAbstract())))))
        // Connection scope: Netty socket reads (brackets the recv syscall and the inline part of
        // request handling with the channel id, which keys the server-span insert).
        // The NIO and epoll transports are both covered; io_uring and KQueue are not.
        .type(named("io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe"))
        .transform(
            (builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(NettyRead.class).on(named("read").and(takesArguments(0)))))
        .type(named("io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe"))
        .transform(
            (builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(NettyRead.class).on(named("epollInReady").and(takesArguments(0)))))
        // Connection scope: Netty inbound handler entry (channelRead). The hop between event-loop
        // groups goes through a hidden-class lambda that cannot be instrumented, so the receiving
        // side recovers the id from ctx.channel(). Ktor's NettyApplicationCallHandler launches the
        // call coroutine UNDISPATCHED inside this method, so the lineage starts here.
        .type(
            hasSuperType(named("io.netty.channel.ChannelInboundHandler"))
                .and(nameStartsWith("io.netty").or(nameStartsWith("io.ktor"))))
        .transform(
            (builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(HandlerChannelRead.class)
                        .on(named("channelRead").and(takesArguments(2)))))
        // Connection scope: ktor-network socket I/O coroutines (CIO server / client engines).
        .type(named("io.ktor.network.sockets.NIOSocketImpl"))
        .transform(
            (builder, type, cl, module, pd) ->
                builder.visit(
                    Advice.to(SocketAttach.class)
                        .on(named("attachForReadingImpl").or(named("attachForWritingImpl")))))
        .installOn(inst);

    System.err.println("[obicoro] agent installed");
  }

  @SuppressWarnings("unused")
  public static final class TaskCreated {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object task) {
      Track.created(task);
    }
  }

  @SuppressWarnings("unused")
  public static final class Dispatched {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(1) Object task) {
      Track.dispatched(task);
    }
  }

  @SuppressWarnings("unused")
  public static final class TaskRun {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This Object task) {
      return Track.runEnter(task);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long prev) {
      Track.runExit(prev);
    }
  }

  @SuppressWarnings("unused")
  public static final class NettyRead {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This Object unsafe) {
      return Track.scopeEnterOuter(unsafe);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long prev) {
      Track.scopeExit(prev);
    }
  }

  @SuppressWarnings("unused")
  public static final class HandlerChannelRead {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.Argument(0) Object ctx) {
      return Track.scopeEnterCtx(ctx);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long prev) {
      Track.scopeExit(prev);
    }
  }

  @SuppressWarnings("unused")
  public static final class SocketAttach {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This Object socket) {
      return Track.scopeEnter(socket);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long prev) {
      Track.scopeExit(prev);
    }
  }
}
