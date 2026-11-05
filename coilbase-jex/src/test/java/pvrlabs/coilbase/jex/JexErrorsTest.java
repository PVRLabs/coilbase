package pvrlabs.coilbase.jex;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.jex.Jex;
import io.avaje.jex.core.json.JacksonJsonService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.*;

class JexErrorsTest {
    record Input(String name) {}
    record Body(String error) {}

    static final class Missing extends RuntimeException {}

    static int status(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof Missing) return 404;
            if (c instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("23")) return 409;
        }
        return 500;
    }

    static Body body(int status) { return new Body("status-" + status); }

    Jex.Server server;
    int port;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        var app = Jex.create().jsonService(new JacksonJsonService(new ObjectMapper()));
        app.post("/echo", ctx -> {
            var input = ctx.bodyAsClass(Input.class);
            ctx.status(200).json(input);
        });
        app.get("/missing", ctx -> { throw new Missing(); });
        app.get("/conflict", ctx -> { throw new RuntimeException(new SQLException("dup", "23000")); });
        app.get("/boom", ctx -> { throw new IllegalStateException("secret detail"); });
        JexErrors.install(app, JexErrorsTest::status, JexErrorsTest::body).port(0);
        server = app.start();
        port = server.port();
    }

    @AfterEach
    void stop() { server.shutdown(); }

    HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void validBodySucceeds() throws Exception {
        var response = send("POST", "/echo", "{\"name\":\"a\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"a\""));
    }

    @Test void malformedJsonIsBadRequest() throws Exception {
        var response = send("POST", "/echo", "{\"name\":");
        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"status-400\"}", response.body());
    }

    @Test void mismatchedJsonShapeIsBadRequest() throws Exception {
        assertEquals(400, send("POST", "/echo", "[1,2]").statusCode());
    }

    @Test void emptyBodyDoesNotBecomeServerErrorOrLeakDetail() throws Exception {
        var response = send("POST", "/echo", null);
        assertTrue(response.statusCode() == 400, "empty body status " + response.statusCode());
        assertFalse(response.body().contains("Exception"));
    }

    @Test void applicationStatusFunctionClassifiesOtherFailures() throws Exception {
        assertEquals(404, send("GET", "/missing", null).statusCode());
        assertEquals(409, send("GET", "/conflict", null).statusCode());
        var boom = send("GET", "/boom", null);
        assertEquals(500, boom.statusCode());
        assertEquals("{\"error\":\"status-500\"}", boom.body());
        assertFalse(boom.body().contains("secret"));
    }

    @Test void handlerCanBeRegisteredDirectlyAndWithCustomHandlersAlongside() throws Exception {
        stop();
        var app = Jex.create().jsonService(new JacksonJsonService(new ObjectMapper()));
        app.post("/echo", ctx -> ctx.json(ctx.bodyAsClass(Input.class)));
        app.get("/teapot", ctx -> { throw new UnsupportedOperationException(); });
        app.error(UnsupportedOperationException.class, (ctx, failure) -> ctx.status(418).text("custom"));
        app.error(Exception.class, JexErrors.handler(JexErrorsTest::status, JexErrorsTest::body));
        app.port(0);
        server = app.start();
        port = server.port();
        assertEquals(418, send("GET", "/teapot", null).statusCode());
        assertEquals(400, send("POST", "/echo", "{").statusCode());
    }

    @Test void statusOfFindsWrappedJacksonFailure() {
        var wrapped = new RuntimeException("wrapper", new com.fasterxml.jackson.core.JsonParseException(null, "bad"));
        assertEquals(400, JexErrors.statusOf(wrapped, JexErrorsTest::status));
        assertEquals(500, JexErrors.statusOf(new IllegalStateException(), JexErrorsTest::status));
    }
}
