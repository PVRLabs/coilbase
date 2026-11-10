package pvrlabs.coilbase.jex;

import static org.junit.jupiter.api.Assertions.*;

import io.avaje.jex.Jex;
import io.avaje.jex.http.ExceptionHandler;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import pvrlabs.coilbase.Dialects;
import pvrlabs.coilbase.SqlStatement;

class CoilBaseJexApplicationIT {
    record Input(String name) {}
    record Stamp(Instant at) {}
    static final class Missing extends RuntimeException {}

    /** Closeable test datasource that counts close calls. */
    static final class CountingDataSource implements DataSource, AutoCloseable {
        final JdbcDataSource delegate = new JdbcDataSource();
        final AtomicInteger closes = new AtomicInteger();
        CountingDataSource(String name) { delegate.setURL("jdbc:h2:mem:" + name + "_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1"); }
        Throwable closeFailure;
        public void close() throws Exception {
            closes.incrementAndGet();
            if (closeFailure instanceof Exception failure) throw failure;
            if (closeFailure instanceof Error failure) throw failure;
        }
        public Connection getConnection() throws SQLException { return delegate.getConnection(); }
        public Connection getConnection(String u, String p) throws SQLException { return delegate.getConnection(u, p); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter out) {}
        public void setLoginTimeout(int seconds) {}
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
        public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("unsupported"); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }

    /** Test application: ephemeral port, H2, a few routes. Hooks are overridden per test via the fields. */
    static class TestApp extends CoilBaseJexApplication {
        final CountingDataSource ds;
        TestApp(String name) { ds = new CountingDataSource(name); }
        boolean withDataSource = true;
        RuntimeException routesFailure;
        @Override protected DataSource createPrimaryDataSource() { return withDataSource ? ds : null; }
        @Override protected Dialects primaryDialect() { return Dialects.H2; }
        @Override protected void registerRoutes(Jex jex) {
            if (routesFailure != null) throw routesFailure;
            jex.post("/echo", ctx -> ctx.json(ctx.bodyAsClass(Input.class)));
            jex.get("/stamp", ctx -> ctx.json(new Stamp(Instant.parse("2026-01-02T03:04:05Z"))));
            jex.get("/missing", ctx -> { throw new Missing(); });
            jex.get("/boom", ctx -> { throw new IllegalStateException("secret detail"); });
            jex.get("/dialect", ctx -> ctx.text(db().sql(SqlStatement.of("default").h2("h2"))));
            if (withDataSource) {
                db().executeScript("CREATE TABLE initialized(id INT); INSERT INTO initialized VALUES (1)");
                jex.get("/one", ctx -> ctx.text(db().query("SELECT id FROM initialized",
                        q -> q.mapTo(Integer.class).one()).toString()));
                jex.get("/database-failure", ctx -> db().query("SELECT * FROM absent_table",
                        q -> q.mapTo(Integer.class).list()));
            }
        }
    }

    String databaseName;
    @BeforeEach void nameDatabase(TestInfo test) {
        databaseName = "CoilBaseJexApplicationIT_" + test.getTestMethod().orElseThrow().getName();
    }

    final HttpClient client = HttpClient.newHttpClient();
    Jex.Server server;

    @AfterEach
    void stop() { if (server != null) server.shutdown(); }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void staticResourcesAreDisabledByDefault() throws Exception {
        var app = new TestApp(databaseName);
        app.withDataSource = false;
        server = app.start(0);
        assertEquals(404, send("GET", "/app.js", null).statusCode());
        assertThrows(IllegalStateException.class, app::enableStaticResources);
    }

    @Test void staticResourcesAndApplicationRoutesCoexist() throws Exception {
        var app = new TestApp(databaseName) {
            @Override protected void registerRoutes(Jex jex) {
                jex.get("/fragment", ctx -> ctx.contentType("text/html").text("<b>fragment</b>"));
                jex.get("/overlap", ctx -> ctx.text("dynamic"));
            }
        };
        app.withDataSource = false;
        assertSame(app, app.enableStaticResources());
        server = app.start(0);
        assertEquals("<h1>Welcome</h1>\n", send("GET", "/", null).body());
        assertEquals("dynamic", send("GET", "/overlap", null).body());
        assertEquals("<b>fragment</b>", send("GET", "/fragment", null).body());
        for (var entry : Map.of("/app.js", "text/javascript", "/styles.css", "text/css", "/favicon.svg", "image/svg+xml", "/index.html", "text/html").entrySet()) {
            var response = send("GET", entry.getKey(), null);
            assertEquals(200, response.statusCode(), entry.getKey());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(entry.getValue()), entry.getKey());
        }
        var head = send("HEAD", "/styles.css", null);
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(404, send("GET", "/absent", null).statusCode());
        assertNotEquals(200, send("GET", "/%2e%2e/secret.txt", null).statusCode());
    }

    @Test void explicitRootRouteTakesPrecedenceOverWelcomePage() throws Exception {
        var app = new TestApp(databaseName) {
            @Override protected void registerRoutes(Jex jex) {
                jex.get("/", ctx -> ctx.text("dynamic root"));
            }
        };
        app.withDataSource = false;
        server = app.enableStaticResources().start(0);
        assertEquals("dynamic root", send("GET", "/", null).body());
    }

    @Test void staticResourcesCannotBeEnabledAfterFailedStartup() {
        var app = new TestApp(databaseName);
        app.routesFailure = new IllegalArgumentException("startup failed");
        assertThrows(IllegalArgumentException.class, () -> app.start(0));
        assertThrows(IllegalStateException.class, app::enableStaticResources);
    }

    @Test void staticResourcesAndMonitoringCanBeChainedInEitherOrder() throws Exception {
        for (boolean monitoringFirst : new boolean[]{false, true}) {
            var app = new TestApp(databaseName);
            app.withDataSource = false;
            var configured = monitoringFirst
                    ? app.enableMonitoring().enableStaticResources()
                    : app.enableStaticResources().enableMonitoring();
            assertSame(app, configured);
            server = configured.start(0);
            assertEquals(200, send("GET", "/app.js", null).statusCode());
            assertEquals(200, send("GET", "/statlite/metrics", null).statusCode());
            assertEquals(200, send("GET", "/stamp", null).statusCode());
            server.shutdown();
            server = null;
        }
    }

    @Test void startsServesQueriesThroughContextAndClosesDataSourceOnShutdown() throws Exception {
        var app = new TestApp(databaseName);
        server = app.start(0);
        assertEquals("1", send("GET", "/one", null).body());
        assertEquals(0, app.ds.closes.get());
        server.shutdown();
        server.shutdown();
        server = null;
        assertEquals(1, app.ds.closes.get());
    }

    @Test void autoCloseableCleanupFailurePreservesStartupFailureAndExceptionHandling() {
        for (var cleanupFailure : new Throwable[]{new SQLException("checked close"),
                new IllegalStateException("runtime close"), new AssertionError("error close")}) {
            var app = new TestApp(databaseName);
            app.routesFailure = new IllegalArgumentException("startup failed");
            app.ds.closeFailure = cleanupFailure;
            var original = assertThrows(IllegalArgumentException.class, () -> app.start(0));
            assertSame(app.routesFailure, original);
            assertEquals(1, original.getSuppressed().length);
            if (cleanupFailure instanceof RuntimeException || cleanupFailure instanceof Error) {
                assertSame(cleanupFailure, original.getSuppressed()[0]);
            } else {
                assertInstanceOf(IllegalStateException.class, original.getSuppressed()[0]);
                assertSame(cleanupFailure, original.getSuppressed()[0].getCause());
            }
            assertEquals(1, app.ds.closes.get());
            assertThrows(IllegalStateException.class, () -> app.start(0));
            assertEquals(1, app.ds.closes.get());
        }
    }

    @Test void startWithoutDataSourceServesHttpAndDbIsUnavailable() throws Exception {
        var app = new TestApp(databaseName);
        app.withDataSource = false;
        server = app.start(0);
        assertEquals(200, send("GET", "/stamp", null).statusCode());
        assertThrows(IllegalStateException.class, () -> new TestApp(databaseName) { { withDataSource = false; } }.db());
        assertEquals(0, app.ds.closes.get());
    }

    @Test void startTwiceIsRejected() {
        var app = new TestApp(databaseName);
        server = app.start(0);
        assertThrows(IllegalStateException.class, () -> app.start(0));
    }

    @Test void startWithDefaultPortUsesEnvironmentOrFallback() {
        String port = System.getenv("PORT");
        int fallback = port == null || port.isBlank() ? 0 : -1;
        server = new TestApp(databaseName).startWithDefaultPort(fallback);
        if (port == null || port.isBlank() || port.equals("0")) assertTrue(server.port() > 0);
        else assertEquals(Integer.parseInt(port), server.port());
    }

    @Test void invalidErrorMappingClosesDatasourceOnStartupFailure() {
        var app = new TestApp(databaseName) {
            @Override protected void configureErrorHandling() { mapError(Missing.class, 200); }
        };
        assertThrows(IllegalArgumentException.class, () -> app.start(0));
        assertEquals(1, app.ds.closes.get());
    }

    @Test void routeRegistrationFailureClosesDataSourceAndPropagates() {
        var app = new TestApp(databaseName);
        app.routesFailure = new IllegalArgumentException("bad routes");
        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals("bad routes", failure.getMessage());
        assertEquals(1, app.ds.closes.get());
    }

    @Test void missingDialectUsesDefaultSql() throws Exception {
        var ds = new CountingDataSource(databaseName);
        var app = new CoilBaseJexApplication() {
            @Override protected DataSource createPrimaryDataSource() { return ds; }
            @Override protected void registerRoutes(Jex jex) {
                jex.get("/dialect", ctx -> ctx.text(db().sql(SqlStatement.of("default").h2("h2"))));
                jex.get("/one", ctx -> ctx.text(db().query(SqlStatement.of("SELECT 1").h2("SELECT 2"),
                        q -> q.mapTo(Integer.class).one()).toString()));
            }
        };
        server = app.start(0);
        assertEquals("default", send("GET", "/dialect", null).body());
        assertEquals("1", send("GET", "/one", null).body());
    }

    @Test void nullDialectUsesDefaultSql() throws Exception {
        server = new TestApp(databaseName) {
            @Override protected Dialects primaryDialect() { return null; }
        }.start(0);
        assertEquals("default", send("GET", "/dialect", null).body());
    }

    @Test void explicitDialectStillSelectsSqlOverride() throws Exception {
        server = new TestApp(databaseName) {
            @Override protected Dialects primaryDialect() { return Dialects.H2; }
        }.start(0);
        assertEquals("h2", send("GET", "/dialect", null).body());
    }

    @Test void dataSourceCreationFailurePropagatesWithNothingToClose() {
        var failure = new IllegalStateException("no pool");
        var app = new TestApp(databaseName) {
            @Override protected DataSource createPrimaryDataSource() { throw failure; }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, app::start));
        assertEquals(0, app.ds.closes.get());
    }

    @Test void schemaInitializationFailurePreventsStartupAndClosesDataSource() {
        var app = new TestApp(databaseName) {
            @Override protected void registerRoutes(Jex jex) {
                db().executeScript("CREATE TABLE broken(");
            }
        };
        var failure = assertThrows(RuntimeException.class, () -> app.start(0));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        assertEquals(1, app.ds.closes.get());
    }

    @Test void jexStartFailureClosesDataSourceExactlyOnce() throws IOException {
        try (var occupied = new ServerSocket(0)) {
            var app = new TestApp(databaseName);
            assertThrows(RuntimeException.class, () -> app.start(occupied.getLocalPort()));
            assertEquals(1, app.ds.closes.get());
        }
    }

    @Test void defaultErrorHandlingCoversMalformedJsonAndUnexpectedFailures() throws Exception {
        server = new TestApp(databaseName).start(0);
        var malformed = send("POST", "/echo", "{\"name\":");
        assertEquals(400, malformed.statusCode());
        assertEquals("{\"error\":\"bad_request\"}", malformed.body());
        for (var path : new String[]{"/boom", "/database-failure"}) {
            var failure = send("GET", path, null);
            assertEquals(500, failure.statusCode());
            assertEquals("{\"error\":\"internal_error\"}", failure.body());
        }
        assertEquals(200, send("POST", "/echo", "{\"name\":\"a\"}").statusCode());
    }

    @Test void customErrorConfigurationPreservesApplicationContract() throws Exception {
        server = new TestApp(databaseName) {
            @Override protected void configureErrorHandling(Jex jex) {
                JexErrors.install(jex, failure -> failure instanceof Missing ? 404 : 500,
                        status -> Map.of("code", status));
            }
        }.start(0);
        var missing = send("GET", "/missing", null);
        assertEquals(404, missing.statusCode());
        assertEquals("{\"code\":404}", missing.body());
        assertEquals("{\"code\":400}", send("POST", "/echo", "[1]").body());
    }

    @Test void errorHandlerCanBeReplacedEntirely() throws Exception {
        server = new TestApp(databaseName) {
            @Override protected void configureErrorHandling(Jex jex) {
                jex.error(Missing.class, (ctx, failure) -> ctx.status(418).text("teapot"));
                jex.error(Exception.class, (ExceptionHandler<Exception>) (ctx, failure) -> ctx.status(599).text("custom"));
            }
        }.start(0);
        assertEquals(418, send("GET", "/missing", null).statusCode());
        assertEquals(599, send("GET", "/boom", null).statusCode());
    }

    @Test void defaultJsonWritesDatesAsIsoStrings() throws Exception {
        server = new TestApp(databaseName).start(0);
        assertEquals("{\"at\":\"2026-01-02T03:04:05Z\"}", send("GET", "/stamp", null).body());
    }

}
