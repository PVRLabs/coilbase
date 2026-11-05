package consumer;

import io.avaje.jex.Jex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pvrlabs.coilbase.jex.CoilBaseJexApplication;
import static org.junit.jupiter.api.Assertions.*;

class JexConsumerTest {
    static class App extends CoilBaseJexApplication {
        @Override protected void registerRoutes(Jex jex) {
            jex.get("/dates", ctx -> ctx.json(Map.of(
                "instant", Instant.parse("2026-01-02T03:04:05Z"),
                "date", LocalDate.of(2026, 1, 2),
                "local", LocalDateTime.of(2026, 1, 2, 3, 4, 5))));
        }
    }

    @Test void h2RemainsAnApplicationSuppliedDependency() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.h2.jdbcx.JdbcConnectionPool"));
    }

    @Test void defaultHttpJsonHasJavaTimeSupportFromTransitiveDependencies() throws Exception {
        var app = new App();
        var server = app.start(0);
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/dates")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var body = response.body();
            assertTrue(body.contains("\"instant\":\"2026-01-02T03:04:05Z\""), body);
            assertTrue(body.contains("\"date\":\"2026-01-02\""), body);
            assertTrue(body.contains("\"local\":\"2026-01-02T03:04:05\""), body);
        } finally {
            server.shutdown();
        }
    }
}
