package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.jex.Jex;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("slow")
class StatLiteSmokeIT {
    @TempDir Path temp;

    @Test void statLiteCollectsApplicationAndDatabaseHealth() throws Exception {
        String binary = System.getenv("STATLITE_BIN");
        assertNotNull(binary, "Set STATLITE_BIN to the StatLite executable to run slow tests");
        var app = new CoilBaseJexApplication() {
            @Override protected DataSource createPrimaryDataSource() {
                var h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:statlite_smoke;DB_CLOSE_DELAY=-1");
                return h2;
            }
            @Override protected void registerRoutes(Jex jex) {
                jex.get("/hello", ctx -> ctx.text("hello"));
            }
        };
        var server = app.enableMonitoring().start(0);
        Process statlite = null;
        var log = temp.resolve("statlite.log");
        try {
            int port;
            try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            var config = temp.resolve("statlite.yaml");
            Files.writeString(config, """
                    server:
                      listen: "127.0.0.1:%d"
                    storage:
                      sqlite_path: "statlite.sqlite"
                    polling:
                      interval: "1s"
                    targets:
                      - name: coilbase
                        type: statlite-metrics
                        url: "http://127.0.0.1:%d/statlite/metrics"
                    """.formatted(port, server.port()));
            statlite = new ProcessBuilder(binary, "--config", config.toString())
                    .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            var mapper = new ObjectMapper();
            var url = URI.create("http://127.0.0.1:" + port + "/api/v1/status?target=coilbase");
            JsonNode last = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && statlite.isAlive()) {
                try {
                    var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        last = mapper.readTree(response.body());
                        if ("coilbase".equals(last.path("target").asText())
                                && "statlite-metrics".equals(last.path("type").asText())
                                && "ok".equals(last.path("collection_status").asText())
                                && "UP".equals(last.path("application_health").asText())
                                && "UP".equals(last.path("dependency_health").asText())
                                && !last.path("last_successful_poll_at").isNull()
                                && !last.path("last_successful_poll_at").isMissingNode()) return;
                    }
                } catch (IOException ignored) {
                    // StatLite may not be listening yet.
                }
                Thread.sleep(100);
            }
            fail("StatLite did not collect healthy status; last=" + last + ", log="
                    + (Files.exists(log) ? Files.readString(log) : "unavailable"));
        } finally {
            try {
                if (statlite != null) {
                    statlite.destroy();
                    try {
                        if (!statlite.waitFor(5, TimeUnit.SECONDS)) {
                            statlite.destroyForcibly();
                            assertTrue(statlite.waitFor(5, TimeUnit.SECONDS), "StatLite did not terminate");
                        }
                    } finally {
                        if (statlite.isAlive()) statlite.destroyForcibly();
                    }
                }
            } finally {
                server.shutdown();
            }
        }
    }
}
