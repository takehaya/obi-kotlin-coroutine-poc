import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;

// Control case: a thread-per-request blocking server. The same thread that
// receives the request also performs the outgoing call via HttpURLConnection
// (unlike java.net.http, its socket I/O stays on the calling thread), so OBI's
// same-thread correlation works out of the box.
public class Main {
    public static void main(String[] args) throws Exception {
        String backend = System.getenv().getOrDefault("BACKEND_URL", "http://localhost:8081");
        HttpServer server = HttpServer.create(new InetSocketAddress(8082), 0);
        // JFRONT_EXECUTOR=virtual runs each request on a JDK 21 virtual thread,
        // to compare OBI's virtual-thread correlation against coroutines.
        boolean virtual = "virtual".equals(System.getenv("JFRONT_EXECUTOR"));
        server.setExecutor(
            virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(8));
        server.createContext("/call", exchange -> {
            HttpURLConnection conn = (HttpURLConnection) URI.create(backend + "/work").toURL().openConnection();
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = "java:" + new String(in.readAllBytes());
            }
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        // Logged so a saved container log proves which control variant produced a result set.
        System.out.println("jfront listening on 8082 -> " + backend
            + " (executor: " + (virtual ? "virtual threads" : "fixed pool of 8") + ")");
    }
}
