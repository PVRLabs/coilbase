package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.jex.Jex;
import io.avaje.jex.http.NotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringIT {
    static class Bad extends RuntimeException {}
    static class App extends CoilBaseJexApplication {
        @Override protected void configureErrorHandling() {
            mapError(NotFoundException.class, 404, "missing");
            mapError(Bad.class, 422, "invalid");
        }
        @Override protected void registerRoutes(Jex jex) {
            jex.get("/ok", ctx -> { try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } ctx.text("ok"); });
            jex.get("/bad", ctx -> { throw new Bad(); });
            jex.get("/missing", ctx -> { throw new NotFoundException("missing"); });
            jex.get("/boom", ctx -> { throw new IllegalStateException("private"); });
        }
    }
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private Jex.Server server;
    @AfterEach void stop() { if (server != null) server.shutdown(); }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void disabledByDefaultAndCannotEnableAfterStart() throws Exception {
        var app = new App();
        server = app.start(0);
        assertEquals(404, get("/statlite/metrics").statusCode());
        assertThrows(IllegalStateException.class, app::enableMonitoring);
    }

    @Test void countsCompletedRequestsAndPreservesErrorMappings() throws Exception {
        var app = new App();
        assertSame(app, app.enableMonitoring());
        server = app.start(0);
        assertEquals(200, get("/ok").statusCode());
        var bad = get("/bad");
        assertEquals(422, bad.statusCode());
        assertEquals("{\"error\":\"invalid\"}", bad.body());
        assertEquals(404, get("/missing").statusCode());
        var unmatched = get("/unmatched");
        assertEquals(404, unmatched.statusCode());
        assertEquals("{\"error\":\"missing\"}", unmatched.body());
        var failure = get("/boom");
        assertEquals(500, failure.statusCode());
        assertEquals("{\"error\":\"internal_error\"}", failure.body());
        var response = get("/statlite/metrics");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var body = mapper.readTree(response.body());
        assertEquals("statlite-metrics/v1", body.path("schema").asText());
        assertEquals("UP", body.path("status").asText());
        var metrics = body.path("metrics");
        assertEquals(5, metrics.path("requests_total").asInt());
        assertEquals(2, metrics.path("responses_404_total").asInt());
        assertEquals(3, metrics.path("responses_4xx_total").asInt());
        assertEquals(1, metrics.path("responses_5xx_total").asInt());
        assertTrue(metrics.path("request_duration_seconds_total").asDouble() >= 0.015);
        assertFalse(body.has("database_status"));
        var repeated = mapper.readTree(get("/statlite/metrics").body()).path("metrics");
        for (var name : new String[]{"requests_total", "responses_404_total", "responses_4xx_total",
                "responses_5xx_total", "request_duration_seconds_total"}) {
            assertEquals(metrics.path(name), repeated.path(name), name);
        }
        var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/statlite/metrics"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(404, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(5, mapper.readTree(get("/statlite/metrics").body()).path("metrics").path("requests_total").asInt());
    }

    @Test void customJexNotFoundHandlerIsPreserved() throws Exception {
        var app = new App() {
            @Override protected void configureErrorHandling(Jex jex) {
                jex.error(NotFoundException.class, (ctx, error) -> ctx.status(410).text("custom missing"));
            }
        };
        server = app.enableMonitoring().start(0);
        var result = get("/unknown");
        assertEquals(410, result.statusCode());
        assertEquals("custom missing", result.body());
        var metrics = mapper.readTree(get("/statlite/metrics").body()).path("metrics");
        assertEquals(1, metrics.path("requests_total").asInt());
        assertEquals(1, metrics.path("responses_4xx_total").asInt());
        assertEquals(0, metrics.path("responses_404_total").asInt());
    }

    @Test void primaryDatabaseHealthIsCachedAndRecovers() throws Exception {
        var h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:monitoring;DB_CLOSE_DELAY=-1");
        var available = new AtomicBoolean(true);
        var connections = new AtomicInteger();
        var initialCheck = new CountDownLatch(1);
        var source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        initialCheck.countDown();
                        if (!available.get()) throw new SQLException("database unavailable");
                    }
                    try { return method.invoke(h2, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var app = new App() {
            @Override protected DataSource createPrimaryDataSource() { return source; }
        };
        server = app.enableMonitoring().start(0);
        assertTrue(initialCheck.await(5, TimeUnit.SECONDS));
        app.monitoring.refreshDatabaseHealth();
        assertEquals("UP", mapper.readTree(get("/statlite/metrics").body()).path("database_status").asText());
        int checked = connections.get();
        available.set(false);
        assertEquals("UP", mapper.readTree(get("/statlite/metrics").body()).path("database_status").asText());
        assertEquals(checked, connections.get());
        app.monitoring.refreshDatabaseHealth();
        var down = mapper.readTree(get("/statlite/metrics").body());
        assertEquals("DOWN", down.path("database_status").asText());
        assertEquals("UP", down.path("status").asText());
        available.set(true);
        app.monitoring.refreshDatabaseHealth();
        assertEquals("UP", mapper.readTree(get("/statlite/metrics").body()).path("database_status").asText());
    }

    @Test void omitsDatabaseStatusUntilInitialCheckCompletes() throws Exception {
        var h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:monitoring_initial;DB_CLOSE_DELAY=-1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("check timed out");
                    }
                    try { return method.invoke(h2, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var app = new App() {
            @Override protected DataSource createPrimaryDataSource() { return source; }
        };
        try {
            server = app.enableMonitoring().start(0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var body = mapper.readTree(get("/statlite/metrics").body());
            assertFalse(body.has("database_status"));
            assertEquals("UP", body.path("status").asText());
        } finally {
            release.countDown();
        }
        app.monitoring.refreshDatabaseHealth();
        assertEquals("UP", mapper.readTree(get("/statlite/metrics").body()).path("database_status").asText());
    }
}
