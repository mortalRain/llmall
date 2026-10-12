import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** 临时：把 frontend 目录用 HTTP 提供出来（避免 file:// 的 CORS 限制），用于真实渲染测量。 */
public class StaticServer {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("D:/dsh/frontend");
        HttpServer server = HttpServer.create(new InetSocketAddress(5500), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/")) {
                p = "/index.html";
            }
            Path f = root.resolve(p.substring(1));
            byte[] body;
            String type = p.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
            if (Files.exists(f) && !Files.isDirectory(f)) {
                body = Files.readAllBytes(f);
            } else {
                body = "404".getBytes();
                type = "text/plain";
            }
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        System.out.println("static server on http://localhost:5500");
        Thread.sleep(1000L * 60 * 20);
    }
}
