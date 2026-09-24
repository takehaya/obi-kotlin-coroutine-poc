import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// CLIENT_ENGINE picks the backend client's engine: cio (default), okhttp or java.
// CLIENT_POOL=<n> (CIO only) turns on pipelining over at most n connections, so concurrent
// requests really share connections; by default CIO opens one connection per call.
val client: HttpClient = when (System.getenv("CLIENT_ENGINE")) {
    "okhttp" -> HttpClient(io.ktor.client.engine.okhttp.OkHttp)
    "java" -> HttpClient(io.ktor.client.engine.java.Java)
    else -> System.getenv("CLIENT_POOL")?.toIntOrNull()?.let { n ->
        HttpClient(CIO) {
            engine {
                pipelining = true
                endpoint.maxConnectionsPerRoute = n
            }
        }
    } ?: HttpClient(CIO)
}

// Backend calls made by one app-scoped worker coroutine that the first /shared request starts.
val sharedQueue: Channel<CompletableDeferred<String>> by lazy {
    Channel<CompletableDeferred<String>>(Channel.UNLIMITED).also { queue ->
        CoroutineScope(Dispatchers.IO).launch {
            for (reply in queue) reply.complete(client.get("$backendUrl/work").bodyAsText())
        }
    }
}
val backendUrl: String = System.getenv("BACKEND_URL") ?: "http://localhost:8081"

// KTOR_ENGINE=cio switches the server engine to CIO (default: Netty), to check
// whether the agent's correlation is engine-dependent.
// NETTY_TRANSPORT=epoll lets Netty pick the native epoll transport; the default stays
// NIO, which is what the published numbers were measured on.
fun main() {
    if (System.getenv("NETTY_TRANSPORT") == "epoll") {
        // Fail fast: a missing/incompatible native lib would otherwise fall back to NIO
        // and mislabel the run.
        io.netty.channel.epoll.Epoll.ensureAvailability()
    } else {
        System.setProperty("io.netty.transport.noNative", "true")
    }
    System.err.println(
        "[frontend] netty transport=" +
            if (io.netty.channel.epoll.Epoll.isAvailable()) "epoll" else "nio"
    )
    if (System.getenv("KTOR_ENGINE") == "cio") {
        embeddedServer(io.ktor.server.cio.CIO, port = 8080) { app() }.start(wait = true)
    } else {
        embeddedServer(Netty, port = 8080) { app() }.start(wait = true)
    }
}

fun Application.app() {
    routing {
        // (a) call the backend with no extra suspension boundary
        get("/direct") {
            val r = client.get("$backendUrl/work").bodyAsText()
            call.respondText("direct:$r")
        }
        // (b) delay and withContext(IO) before calling the backend
        get("/hop") {
            delay(30)
            val r = withContext(Dispatchers.IO) {
                client.get("$backendUrl/work").bodyAsText()
            }
            call.respondText("hop:$r")
        }
        // (d) the backend call is made by a long-lived worker coroutine started by the first
        // /shared request, not by the request's own coroutine
        get("/shared") {
            val reply = CompletableDeferred<String>()
            sharedQueue.send(reply)
            call.respondText("shared:${reply.await()}")
        }
        // (c) two parallel calls via async on the IO dispatcher
        get("/parallel") {
            val rs = withContext(Dispatchers.IO) {
                (1..2).map {
                    async { client.get("$backendUrl/work").bodyAsText() }
                }.awaitAll()
            }
            call.respondText("parallel:${rs.size}")
        }
    }
}
