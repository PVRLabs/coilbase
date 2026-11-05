package pvrlabs.coilbase.example.hello;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.ServerSocket;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.h2.jdbcx.JdbcConnectionPool;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import pvrlabs.coilbase.Dialects;
import pvrlabs.coilbase.RepositoryContext;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises generated HTTP routes, transaction rollback, schema initialization and pool cleanup. */
class HelloApplicationTest {
    /** Captures the real application pool and resets its named in-memory database between runs. */
    static class TestApplication extends HelloApplication {
        JdbcConnectionPool createdPool;

        @Override protected DataSource createPrimaryDataSource() {
            createdPool = (JdbcConnectionPool) super.createPrimaryDataSource();
            try (var connection = createdPool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP ALL OBJECTS");
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return createdPool;
        }
    }

    private JdbcConnectionPool pool() {
        return JdbcConnectionPool.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test void httpCreateReadValidationAndShutdown() throws Exception {
        var app = new TestApplication();
        var server = app.start(0);
        try {
            var client = HttpClient.newHttpClient();
            var metricsUrl = URI.create("http://127.0.0.1:" + server.port() + "/statlite/metrics");
            assertEquals(404, client.send(HttpRequest.newBuilder(metricsUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var url = URI.create("http://127.0.0.1:" + server.port() + "/greetings");
            var empty = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, empty.statusCode());
            assertEquals("[]", empty.body());
            var created = client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ada\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            assertTrue(created.body().contains("Hello, Ada!"));
            var stored = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("[" + created.body() + "]", stored.body());
            for (var input : new String[]{"{\"name\":\" \"}", "null", "{", "{}", "",
                    "{\"name\":\"" + "a".repeat(81) + "\"}"}) {
                var invalid = client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode(), input);
                assertEquals("{\"error\":\"bad_request\"}", invalid.body(), input);
            }
            assertEquals(stored.body(), client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            server.shutdown();
        }
        assertThrows(IllegalStateException.class, app.createdPool::getConnection);
    }

    @Test void optionalMonitoringCountsHelloRequestsWithoutCountingMetrics() throws Exception {
        var app = new TestApplication();
        // Monitoring is optional and explicitly enabled: new HelloApplication().enableMonitoring().
        var server = app.enableMonitoring().start(0);
        try {
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + server.port();
            var greetings = URI.create(base + "/greetings");
            assertEquals(200, client.send(HttpRequest.newBuilder(greetings).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(greetings).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            var request = HttpRequest.newBuilder(URI.create(base + "/statlite/metrics")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var body = new ObjectMapper().readTree(response.body());
            assertEquals("statlite-metrics/v1", body.path("schema").asText());
            assertEquals("UP", body.path("status").asText());
            var metrics = body.path("metrics");
            assertEquals(3, metrics.path("requests_total").asInt());
            assertEquals(1, metrics.path("responses_404_total").asInt());
            assertEquals(2, metrics.path("responses_4xx_total").asInt());
            assertEquals(0, metrics.path("responses_5xx_total").asInt());
            var repeated = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, repeated.statusCode());
            var repeatedMetrics = new ObjectMapper().readTree(repeated.body()).path("metrics");
            for (var name : new String[]{"requests_total", "responses_404_total", "responses_4xx_total",
                    "responses_5xx_total", "request_duration_seconds_total"}) {
                assertEquals(metrics.path(name), repeatedMetrics.path(name), name);
            }
        } finally {
            server.shutdown();
        }
        assertThrows(IllegalStateException.class, app.createdPool::getConnection);
    }

    @Test void databaseFailureUsesSanitizedJsonError() throws Exception {
        var app = new TestApplication();
        var server = app.start(0);
        try {
            Jdbi.create(app.createdPool).useHandle(handle -> handle.execute("DROP TABLE greetings"));
            var request = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + server.port() + "/greetings")).GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertEquals("{\"error\":\"internal_error\"}", response.body());
        } finally {
            server.shutdown();
        }
        assertThrows(IllegalStateException.class, app.createdPool::getConnection);
    }

    @Test void serviceJoinsTransactionAndRollsBackWithCaller() {
        var pool = pool();
        try {
            var db = new RepositoryContext(Jdbi.create(pool), Dialects.H2);
            db.executeScriptResource("/schema.sql");
            var repository = new GreetingRepository(db);
            var service = new GreetingService(db, repository);
            assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
                service.greet("Ada");
                throw new IllegalStateException("Abort the outer transaction");
            }));
            assertTrue(repository.findAll().isEmpty());
            var greeting = service.greet("Grace");
            assertEquals(greeting, repository.findAll().getFirst());
        } finally {
            pool.dispose();
        }
    }

    @Test void schemaInitializationIsRepeatableAndPreservesData() {
        var pool = pool();
        try {
            var db = new RepositoryContext(Jdbi.create(pool), Dialects.H2);
            db.executeScriptResource("/schema.sql");
            var repository = new GreetingRepository(db);
            var greeting = new GreetingService(db, repository).greet("Ada");
            db.executeScriptResource("/schema.sql");
            assertEquals(java.util.List.of(greeting), repository.findAll());
        } finally {
            pool.dispose();
        }
    }

    @Test void schemaFailurePreventsStartupAndDisposesPool() {
        var app = new TestApplication() {
            @Override protected void registerRoutes(io.avaje.jex.Jex jex) {
                db().executeScript("CREATE TABLE broken(");
            }
        };
        var failure = assertThrows(RuntimeException.class, () -> app.start(0));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        assertThrows(IllegalStateException.class, app.createdPool::getConnection);
    }

    @Test void failureBeforePoolInitializationKeepsOriginalException() {
        var failure = new IllegalStateException("No datasource");
        var app = new HelloApplication() {
            @Override protected DataSource createPrimaryDataSource() { throw failure; }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> app.start(0)));
    }

    @Test void serverStartFailureDisposesPool() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            var app = new TestApplication();
            assertThrows(RuntimeException.class, () -> app.start(occupied.getLocalPort()));
            assertThrows(IllegalStateException.class, app.createdPool::getConnection);
        }
    }
}
