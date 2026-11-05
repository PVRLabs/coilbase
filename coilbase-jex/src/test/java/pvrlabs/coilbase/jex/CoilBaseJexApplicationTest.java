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
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.*;
import pvrlabs.coilbase.Dialects;
import pvrlabs.coilbase.SqlStatement;

class CoilBaseJexApplicationTest {
    record Input(String name) {}
    record Stamp(Instant at) {}
    static final class Missing extends RuntimeException {}

    /** Closeable test datasource that counts close calls. */
    static final class CountingDataSource implements DataSource, AutoCloseable {
        final JdbcDataSource delegate = new JdbcDataSource();
        final AtomicInteger closes = new AtomicInteger();
        CountingDataSource() { delegate.setURL("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"); }
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
        final CountingDataSource ds = new CountingDataSource();
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
            if (withDataSource) jex.get("/one", ctx -> {
                Integer one = db().query("SELECT 1", q -> q.mapTo(Integer.class).one());
                ctx.text(one.toString());
            });
        }
    }

    /** Real H2 pool, deliberately not AutoCloseable. */
    static class H2PoolApp extends CoilBaseJexApplication {
        final JdbcConnectionPool pool = JdbcConnectionPool.create(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        RuntimeException routesFailure;
        @Override protected DataSource createPrimaryDataSource() { return pool; }
        @Override protected Dialects primaryDialect() { return Dialects.H2; }
        @Override protected void registerRoutes(Jex jex) {
            if (routesFailure != null) throw routesFailure;
            jex.get("/one", ctx -> {
                int one = db().query("SELECT 1", query -> query.mapTo(Integer.class).one());
                ctx.text(Integer.toString(one));
            });
        }
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

    @Test void startsServesQueriesThroughContextAndClosesDataSourceOnShutdown() throws Exception {
        var app = new TestApp();
        server = app.start(0);
        assertEquals("1", send("GET", "/one", null).body());
        assertEquals(0, app.ds.closes.get());
        server.shutdown();
        server.shutdown();
        server = null;
        assertEquals(1, app.ds.closes.get());
    }

    @Test void h2PoolIsDisposedOnShutdown() throws Exception {
        var app = new H2PoolApp();
        server = app.start(0);
        assertEquals("1", send("GET", "/one", null).body());
        server.shutdown();
        server.shutdown();
        server = null;
        assertThrows(IllegalStateException.class, app.pool::getConnection);
    }

    @Test void h2PoolIsDisposedOnStartupFailure() {
        var app = new H2PoolApp();
        app.routesFailure = new IllegalArgumentException("bad routes");
        assertSame(app.routesFailure, assertThrows(IllegalArgumentException.class, () -> app.start(0)));
        assertThrows(IllegalStateException.class, app.pool::getConnection);
    }

    @Test void h2PoolIsDisposedWhenServerCannotBind() throws IOException {
        try (var occupied = new ServerSocket(0)) {
            var app = new H2PoolApp();
            assertThrows(RuntimeException.class, () -> app.start(occupied.getLocalPort()));
            assertThrows(IllegalStateException.class, app.pool::getConnection);
        }
    }

    @Test void autoCloseableCleanupFailurePreservesStartupFailureAndExceptionHandling() {
        for (var cleanupFailure : new Throwable[]{new SQLException("checked close"),
                new IllegalStateException("runtime close"), new AssertionError("error close")}) {
            var app = new TestApp();
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
        var app = new TestApp();
        app.withDataSource = false;
        server = app.start(0);
        assertEquals(200, send("GET", "/stamp", null).statusCode());
        assertThrows(IllegalStateException.class, () -> new TestApp() { { withDataSource = false; } }.db());
        assertEquals(0, app.ds.closes.get());
    }

    @Test void startTwiceIsRejected() {
        var app = new TestApp();
        server = app.start(0);
        assertThrows(IllegalStateException.class, () -> app.start(0));
    }

    @Test void startWithDefaultPortUsesEnvironmentOrFallback() {
        String port = System.getenv("PORT");
        int fallback = port == null || port.isBlank() ? 0 : -1;
        server = new TestApp().startWithDefaultPort(fallback);
        if (port == null || port.isBlank() || port.equals("0")) assertTrue(server.port() > 0);
        else assertEquals(Integer.parseInt(port), server.port());
    }

    @Test void invalidErrorMappingClosesDatasourceOnStartupFailure() {
        var app = new TestApp() {
            @Override protected void configureErrorHandling() { mapError(Missing.class, 200); }
        };
        assertThrows(IllegalArgumentException.class, () -> app.start(0));
        assertEquals(1, app.ds.closes.get());
    }

    @Test void routeRegistrationFailureClosesDataSourceAndPropagates() {
        var app = new TestApp();
        app.routesFailure = new IllegalArgumentException("bad routes");
        var failure = assertThrows(IllegalArgumentException.class, app::start);
        assertEquals("bad routes", failure.getMessage());
        assertEquals(1, app.ds.closes.get());
    }

    @Test void missingDialectFailsStartupAndClosesDatasource() {
        var ds = new CountingDataSource();
        var app = new CoilBaseJexApplication() {
            @Override protected DataSource createPrimaryDataSource() { return ds; }
            @Override protected void registerRoutes(Jex jex) { fail("routes must not be registered"); }
        };
        assertThrows(IllegalStateException.class, () -> app.start(0));
        assertEquals(1, ds.closes.get());
    }

    @Test void nullDialectFailsStartupAndClosesDatasource() {
        var app = new TestApp() {
            @Override protected Dialects primaryDialect() { return null; }
        };
        assertThrows(IllegalStateException.class, () -> app.start(0));
        assertEquals(1, app.ds.closes.get());
    }

    @Test void explicitDialectStillSelectsSqlOverride() throws Exception {
        server = new TestApp() {
            @Override protected Dialects primaryDialect() { return Dialects.H2; }
        }.start(0);
        assertEquals("h2", send("GET", "/dialect", null).body());
    }

    @Test void dataSourceCreationFailurePropagatesWithNothingToClose() {
        var app = new TestApp() {
            @Override protected DataSource createPrimaryDataSource() { throw new IllegalStateException("no pool"); }
        };
        assertEquals("no pool", assertThrows(IllegalStateException.class, app::start).getMessage());
        assertEquals(0, app.ds.closes.get());
    }

    @Test void jexStartFailureClosesDataSourceExactlyOnce() throws IOException {
        try (var occupied = new ServerSocket(0)) {
            var app = new TestApp();
            assertThrows(RuntimeException.class, () -> app.start(occupied.getLocalPort()));
            assertEquals(1, app.ds.closes.get());
        }
    }

    @Test void defaultErrorHandlingCoversMalformedJsonAndUnexpectedFailures() throws Exception {
        server = new TestApp().start(0);
        var malformed = send("POST", "/echo", "{\"name\":");
        assertEquals(400, malformed.statusCode());
        assertEquals("{\"error\":\"bad_request\"}", malformed.body());
        var boom = send("GET", "/boom", null);
        assertEquals(500, boom.statusCode());
        assertEquals("{\"error\":\"internal_error\"}", boom.body());
        assertFalse(boom.body().contains("secret"));
        assertEquals(200, send("POST", "/echo", "{\"name\":\"a\"}").statusCode());
    }

    @Test void customErrorConfigurationPreservesApplicationContract() throws Exception {
        server = new TestApp() {
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
        server = new TestApp() {
            @Override protected void configureErrorHandling(Jex jex) {
                jex.error(Missing.class, (ctx, failure) -> ctx.status(418).text("teapot"));
                jex.error(Exception.class, (ExceptionHandler<Exception>) (ctx, failure) -> ctx.status(599).text("custom"));
            }
        }.start(0);
        assertEquals(418, send("GET", "/missing", null).statusCode());
        assertEquals(599, send("GET", "/boom", null).statusCode());
    }

    @Test void defaultJsonWritesDatesAsIsoStrings() throws Exception {
        server = new TestApp().start(0);
        assertEquals("{\"at\":\"2026-01-02T03:04:05Z\"}", send("GET", "/stamp", null).body());
    }

    @Test void jexErrorsRemainsUsableIndependently() {
        assertEquals(400, JexErrors.statusOf(new RuntimeException(new com.fasterxml.jackson.core.JsonParseException(null, "x")), f -> 500));
    }
}
