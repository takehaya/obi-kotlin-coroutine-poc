import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay

fun main() {
    embeddedServer(Netty, port = 8081) {
        routing {
            get("/work") {
                delay(20)
                call.respondText("ok")
            }
        }
    }.start(wait = true)
}
