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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val client = HttpClient(CIO)
val backendUrl: String = System.getenv("BACKEND_URL") ?: "http://localhost:8081"

// KTOR_ENGINE=cio switches the server engine to CIO (default: Netty), to check
// whether the agent's correlation is engine-dependent.
fun main() {
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
