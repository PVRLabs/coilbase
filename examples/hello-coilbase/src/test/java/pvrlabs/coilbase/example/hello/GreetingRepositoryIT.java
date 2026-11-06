package pvrlabs.coilbase.example.hello;

import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import pvrlabs.coilbase.RepositoryContext;
import static org.junit.jupiter.api.Assertions.*;

class GreetingRepositoryIT {
    private RepositoryContext db;
    private GreetingRepository repository;

    @BeforeEach void setUp(TestInfo test) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:GreetingRepositoryIT_" + test.getTestMethod().orElseThrow().getName()
                + "_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        db = RepositoryContext.create(source);
        db.executeScriptResource("/schema.sql");
        repository = new GreetingRepository(db);
    }

    /** Runs real SQL against H2 and checks that stored rows map to greetings in query order. */
    @Test void insertsAndMapsGreetingsInIdOrder() {
        assertTrue(repository.findAll().isEmpty());
        var ada = new Greeting("1", "Hello, Ada!");
        var grace = new Greeting("2", "Hello, Grace!");
        repository.insert(grace);
        repository.insert(ada);
        assertEquals(List.of(ada, grace), repository.findAll());
    }

    /** Rolls back a repository insert through the caller's transaction, preserving earlier data. */
    @Test void insertsParticipateInCallerTransaction() {
        var existing = new Greeting("1", "Hello, Ada!");
        repository.insert(existing);
        assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
            repository.insert(new Greeting("2", "Hello, Grace!"));
            throw new IllegalStateException("Abort the transaction");
        }));
        assertEquals(List.of(existing), repository.findAll());
    }

    /** Checks that this application's schema script can run again without deleting its data. */
    @Test void schemaInitializationPreservesGreetings() {
        var greeting = new Greeting("1", "Hello, Ada!");
        repository.insert(greeting);
        db.executeScriptResource("/schema.sql");
        assertEquals(List.of(greeting), repository.findAll());
    }
}
