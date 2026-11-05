package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.avaje.jex.Jex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the base application's shared handler and typed error mappings over HTTP. */
class ErrorMappingTest {
    record Input(String name) {}
    static class Problem extends RuntimeException {}
    static final class SpecificProblem extends Problem {}

    static class App extends CoilBaseJexApplication {
        @Override protected void registerRoutes(Jex jex) {
            jex.get("/specific", ctx -> { throw new SpecificProblem(); });
            jex.get("/general", ctx -> { throw new Problem(); });
            jex.get("/wrapped", ctx -> { throw new RuntimeException(new SpecificProblem()); });
            jex.get("/boom", ctx -> { throw new IllegalStateException("secret detail"); });
            jex.post("/echo", ctx -> ctx.json(ctx.bodyAsClass(Input.class)));
        }
    }

    final HttpClient client = HttpClient.newHttpClient();
    Jex.Server server;

    @AfterEach void stop() {
        if (server != null) server.shutdown();
        server = null;
    }

    private void assertResponse(String path, int status, String code) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertEquals("{\"error\":\"" + code + "\"}", response.body());
    }

    private void assertJsonFailure(String body, String code) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + server.port() + "/echo"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"" + code + "\"}", response.body());
    }

    @Test void emptyConfigurationHookKeepsSafeDefaults() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() {}
        }.start(0);
        assertResponse("/boom", 500, "internal_error");
        assertJsonFailure("{", "bad_request");
    }

    @Test void twoArgumentMappingsUseStandardClientCodes() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() {
                mapError(SpecificProblem.class, 400);
                mapError(Problem.class, 404);
            }
        }.start(0);
        assertResponse("/specific", 400, "bad_request");
        assertResponse("/general", 404, "not_found");
        assertResponse("/boom", 500, "internal_error");
    }

    @Test void typesCanHaveDifferentCodesForTheSameStatus() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() {
                mapError(SpecificProblem.class, 400, "invalid_specific");
                mapError(Problem.class, 400, "invalid_general");
            }
        }.start(0);
        assertResponse("/specific", 400, "invalid_specific");
        assertResponse("/general", 400, "invalid_general");
        assertResponse("/boom", 500, "internal_error");
    }

    @Test void specificTypeWinsInEitherRegistrationOrder() throws Exception {
        for (boolean specificFirst : new boolean[]{true, false}) {
            server = new App() {
                @Override protected void configureErrorHandling() {
                    if (specificFirst) {
                        mapError(SpecificProblem.class, 404, "specific");
                        mapError(Problem.class, 409, "general");
                    } else {
                        mapError(Problem.class, 409, "general");
                        mapError(SpecificProblem.class, 404, "specific");
                    }
                }
            }.start(0);
            try {
                assertResponse("/specific", 404, "specific");
                assertResponse("/general", 409, "general");
            } finally {
                stop();
            }
        }
    }

    @Test void resolvesMappedCausesWhenTheWrapperHasNoMapping() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() { mapError(SpecificProblem.class, 404, "missing"); }
        }.start(0);
        assertResponse("/wrapped", 404, "missing");
    }

    @Test void jsonParsingMappingsPreserveCustomBadRequestCode() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() {
                mapError(JsonParseException.class, 400, "invalid_request");
                mapError(MismatchedInputException.class, 400, "invalid_request");
            }
        }.start(0);
        assertJsonFailure("{", "invalid_request");
        assertJsonFailure("[1]", "invalid_request");
        assertJsonFailure("", "invalid_request");
    }

    @Test void laterRegistrationReplacesSameType() throws Exception {
        server = new App() {
            @Override protected void configureErrorHandling() {
                mapError(Problem.class, 400, "old_code");
                mapError(Problem.class, 422, "new_code");
            }
        }.start(0);
        assertResponse("/general", 422, "new_code");
    }

    @Test void rejectsInvalidMappingConfiguration() {
        var app = new App();
        assertThrows(NullPointerException.class, () -> app.mapError(null, 400));
        assertThrows(NullPointerException.class, () -> app.mapError(Problem.class, 400, null));
        assertThrows(IllegalArgumentException.class, () -> app.mapError(Problem.class, 400, " "));
        assertThrows(IllegalArgumentException.class, () -> app.mapError(Problem.class, 200));
        assertThrows(IllegalArgumentException.class, () -> app.mapError(Problem.class, 600));
    }
}
