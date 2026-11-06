package pvrlabs.coilbase.example.hello;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.jex.Jex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import static org.junit.jupiter.api.Assertions.*;

class HelloApplicationIT {
    private Jex.Server server;
    private HttpClient client;
    private URI baseUri;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setUp(TestInfo test) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:HelloApplicationIT_" + test.getTestMethod().orElseThrow().getName()
                + "_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        var app = new HelloApplication() {
            @Override protected DataSource createPrimaryDataSource() { return source; }
        };
        server = app.start(0);
        client = HttpClient.newHttpClient();
        baseUri = URI.create("http://127.0.0.1:" + server.port() + "/greetings");
    }

    @AfterEach void tearDown() {
        try {
            if (client != null) client.close();
        } finally {
            if (server != null) server.shutdown();
        }
    }

    /** Creates a greeting over HTTP, rejects a blank name, and reads the persisted JSON back. */
    @Test void createsAndListsGreetings() throws Exception {
        var empty = client.send(HttpRequest.newBuilder(baseUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, empty.statusCode());
        assertEquals("[]", empty.body());

        var create = HttpRequest.newBuilder(baseUri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ada\"}")).build();
        var created = client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        var greeting = mapper.readValue(created.body(), Greeting.class);
        assertFalse(greeting.id().isBlank());
        assertEquals("Hello, Ada!", greeting.message());

        var invalid = HttpRequest.newBuilder(baseUri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build();
        assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());

        var stored = client.send(HttpRequest.newBuilder(baseUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stored.statusCode());
        assertArrayEquals(new Greeting[]{greeting}, mapper.readValue(stored.body(), Greeting[].class));
    }
}
