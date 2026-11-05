package pvrlabs.coilbase.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.jex.Jex;
import io.avaje.jex.core.json.JacksonJsonService;
import io.avaje.jex.http.NotFoundException;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pvrlabs.coilbase.monitoring.StatLiteMetrics;
import static org.junit.jupiter.api.Assertions.*;

class MetricsExamplesTest {
    private static final String MARKER = "metrics.instrumented";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void jex() throws Exception {
        var metrics = new StatLiteMetrics();
        var app = Jex.create().jsonService(new JacksonJsonService(json));
        app.filter((ctx, chain) -> {
            if (ctx.path().equals(StatLiteMetrics.PATH)) { chain.proceed(); return; }
            ctx.attribute(MARKER, true);
            long start = System.nanoTime();
            try { chain.proceed(); }
            finally {
                int status = ctx.exchange().getResponseCode();
                metrics.recordRequest(status > 0 ? status : 204, System.nanoTime() - start);
            }
        });
        app.error(NotFoundException.class, (ctx, e) -> {
            if (!ctx.path().equals(StatLiteMetrics.PATH) && ctx.attribute(MARKER) == null)
                metrics.recordRequest(404, 0);
            ctx.status(404).text("Not found");
        });
        app.error(Exception.class, (ctx, e) -> ctx.status(500).text("Error"));
        app.get(StatLiteMetrics.PATH, ctx -> { ctx.header("Cache-Control", "no-store"); ctx.json(metrics.snapshot("UP", null)); });
        app.get("/ok", ctx -> { try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } ctx.text("ok"); });
        app.get("/bad", ctx -> ctx.status(400).text("bad"));
        app.get("/mapped-missing", ctx -> { throw new NotFoundException("missing"); });
        app.get("/boom", ctx -> { throw new IllegalStateException(); });
        var server = app.port(0).start();
        try { check(server.port()); } finally { server.shutdown(); }
    }

    @Test void javalin() throws Exception {
        var metrics = new StatLiteMetrics();
        var app = Javalin.create(config -> {
            config.requestLogger.http((ctx, ms) -> {
                if (!ctx.path().equals(StatLiteMetrics.PATH))
                    metrics.recordRequest(ctx.status().getCode(), (long) (ms * 1_000_000));
            });
            config.routes.get(StatLiteMetrics.PATH, ctx -> {
                ctx.header("Cache-Control", "no-store");
                ctx.json(metrics.snapshot("UP", null));
            });
            config.routes.get("/ok", ctx -> { Thread.sleep(20); ctx.result("ok"); });
            config.routes.get("/bad", ctx -> ctx.status(400).result("bad"));
            config.routes.get("/mapped-missing", ctx -> ctx.status(404).result("missing"));
            config.routes.get("/boom", ctx -> { throw new IllegalStateException(); });
        }).start(0);
        try { check(app.port()); } finally { app.stop(); }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private void check(int port) throws Exception {
        Map<String, Object> before = json.readValue(get(port, StatLiteMetrics.PATH).body(), Map.class);
        assertEquals("statlite-metrics/v1", before.get("schema"));
        assertEquals("UP", before.get("status"));
        assertEquals(200, get(port, "/ok").statusCode());
        assertEquals(404, get(port, "/missing").statusCode());
        assertEquals(404, get(port, "/mapped-missing").statusCode());
        assertEquals(400, get(port, "/bad").statusCode());
        assertEquals(500, get(port, "/boom").statusCode());
        Map<String, Object> body = json.readValue(get(port, StatLiteMetrics.PATH).body(), Map.class);
        Map<String, Number> m = (Map<String, Number>) body.get("metrics");
        assertEquals(5, m.get("requests_total").intValue());
        assertEquals(2, m.get("responses_404_total").intValue());
        assertEquals(3, m.get("responses_4xx_total").intValue());
        assertEquals(1, m.get("responses_5xx_total").intValue());
        assertTrue(m.get("request_duration_seconds_total").doubleValue() >= 0.015);
        assertEquals(before.get("started_at"), body.get("started_at"));
        Map<String, Number> again = (Map<String, Number>) json.readValue(get(port, StatLiteMetrics.PATH).body(), Map.class).get("metrics");
        assertEquals(m.get("requests_total"), again.get("requests_total"));
    }
}
