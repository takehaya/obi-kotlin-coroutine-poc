import io.ktor.server.application.call
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay

// BACKEND_ENGINE=cio switches the backend's server engine from Netty to CIO.
fun main() {
    val module: io.ktor.server.application.Application.() -> Unit = {
        routing {
            get("/work") {
                delay(20)
                call.respondText("ok")
            }
        }
    }
    if (System.getenv("BACKEND_ENGINE") == "cio") {
        embeddedServer(io.ktor.server.cio.CIO, port = 8081, module = module).start(wait = true)
    } else if (System.getenv("BACKEND_TLS") == "1") {
        // BACKEND_TLS=1 adds HTTPS on 8443 with a throwaway self-signed certificate.
        val keyStore = io.ktor.network.tls.certificates.buildKeyStore {
            certificate("backend") {
                password = "changeit"
                domains = listOf("backend", "localhost")
            }
        }
        embeddedServer(
            Netty,
            configure = {
                connector { port = 8081 }
                sslConnector(keyStore, "backend", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                    port = 8443
                }
            },
            module = module,
        ).start(wait = true)
    } else {
        embeddedServer(Netty, port = 8081, module = module).start(wait = true)
    }
}
