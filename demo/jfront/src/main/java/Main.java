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
        server.setExecutor(Executors.newFixedThreadPool(8));
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
        System.out.println("jfront listening on 8082 -> " + backend);
    }
}
