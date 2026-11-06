package pvrlabs.coilbase;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryContextScriptsIT {
    RepositoryContext db;

    @BeforeEach void setup(TestInfo test) {
        db = RepositoryContext.create(RepositoryIT.fresh(test, "main"), Dialects.H2);
        db.useHandle(handle -> handle.execute("CREATE TABLE script_entries(id INT PRIMARY KEY, message VARCHAR(100))"));
    }

    @Test void executesMultipleStatementsWithJdbiScriptParsing() {
        db.executeScript("""
                INSERT INTO script_entries VALUES (1, 'hello; world');
                INSERT INTO script_entries VALUES (2, 'it''s SQL');
                """);
        assertEquals(List.of("hello; world", "it's SQL"), messages());
    }

    @Test void readsUtf8ClasspathResource() {
        db.executeScriptResource("/scripts/utf8.sql");
        assertEquals(List.of("Olá, 世界; it's SQL", "café"), messages());
    }

    @Test void resolvesApplicationResourcesThroughContextClassLoader(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("application.sql"),
                "INSERT INTO script_entries VALUES (1, 'Grüße');", StandardCharsets.UTF_8);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            db.executeScriptResource("/application.sql");
            assertEquals(List.of("Grüße"), messages());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test void supportsRootRelativePathWithoutSlashAndNullContextClassLoader() {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            db.executeScriptResource("scripts/utf8.sql");
            assertEquals(2, messages().size());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test void missingResourceHasClearError() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> db.executeScriptResource("/missing.sql"));
        assertEquals("Classpath SQL resource not found: /missing.sql", failure.getMessage());
        assertTrue(messages().isEmpty());
    }

    @Test void sqlFailurePropagatesWithoutAnImplicitTransaction() {
        assertThrows(UnableToExecuteStatementException.class, () -> db.executeScript("""
                INSERT INTO script_entries VALUES (1, 'committed by JDBC autocommit');
                INSERT INTO script_entries VALUES (1, 'duplicate');
                """));
        assertEquals(List.of("committed by JDBC autocommit"), messages());
    }

    @Test void textAndResourceScriptsParticipateInCallerTransaction() {
        var failure = new IllegalStateException("Abort the caller's transaction");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
            db.executeScript("INSERT INTO script_entries VALUES (3, 'caller-controlled')");
            db.executeScriptResource("/scripts/utf8.sql");
            assertEquals(3, messages().size());
            throw failure;
        })));
        assertTrue(messages().isEmpty());

        db.transactional(() -> db.executeScriptResource("/scripts/utf8.sql"));
        assertEquals(List.of("Olá, 世界; it's SQL", "café"), messages());
    }

    private List<String> messages() {
        return db.query("SELECT message FROM script_entries ORDER BY id", query -> query.mapTo(String.class).list());
    }
}
