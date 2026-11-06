package pvrlabs.coilbase;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.*;

class UpdateIT {
    RepositoryContext db;

    @BeforeEach
    void setup(TestInfo test) {
        db = RepositoryContext.create(RepositoryIT.fresh(test, "main"), Dialects.H2);
        db.useHandle(handle -> handle.execute("CREATE TABLE entries(id INT PRIMARY KEY, amount INT)"));
    }

    @Test
    void transactionalOverloadsSupportCommonCallSitesAndRollback() {
        db.transactional(() -> db.executeScript("INSERT INTO entries VALUES (1, 10)"));
        int inserted = db.transactional(() -> db.update("INSERT INTO entries VALUES (2, 20)",
                update -> update.execute()));
        assertEquals(1, inserted);
        assertEquals(2, db.transactional(this::count));
        db.transactional(() -> { db.executeScript("INSERT INTO entries VALUES (3, 30)"); });
        assertEquals(3, db.transactional(() -> { return count(); }));

        var failure = new IllegalStateException("rollback");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
            int nested = db.transactional(() -> db.update("INSERT INTO entries VALUES (4, 40)",
                    update -> update.execute()));
            assertEquals(1, nested);
            throw failure;
        })));
        assertEquals(3, count());
        assertThrows(NullPointerException.class, () -> db.transactional((java.util.function.Supplier<Object>) null));
    }

    @Test
    void crudReturnsAffectedRowsWithNamedBinding() {
        assertEquals(1, db.update("INSERT INTO entries VALUES (:id, :amount)",
                update -> update.bind("id", 1).bind("amount", 10).execute()));
        assertEquals(1, db.update("UPDATE entries SET amount = :amount WHERE id = :id",
                update -> update.bind("amount", 20).bind("id", 1).execute()));
        assertEquals(20, db.query("SELECT amount FROM entries WHERE id = 1",
                query -> query.mapTo(Integer.class).one()).intValue());
        assertEquals(0, db.update("UPDATE entries SET amount = 30 WHERE id = :id",
                update -> update.bind("id", 999).execute()));
        assertEquals(1, db.update("DELETE FROM entries WHERE id = :id",
                update -> update.bind("id", 1).execute()));
        assertEquals(0, count());
    }

    @Test
    void statementUsesDialectOverrideWithoutChangingOriginal() {
        var original = SqlStatement.of("INSERT INTO missing_table VALUES (:id, :amount)");
        var selected = original.override(Dialects.H2, "INSERT INTO entries VALUES (:id, :amount)");
        assertEquals(1, db.update(selected,
                update -> update.bind("id", 7).bind("amount", 42).execute()));
        assertEquals("INSERT INTO missing_table VALUES (:id, :amount)", original.sql(Dialects.H2));
        assertEquals(42, db.query("SELECT amount FROM entries WHERE id = 7",
                query -> query.mapTo(Integer.class).one()).intValue());
    }

    @Test
    void callbackFailureClosesStatementButRetainsServiceTransactionUntilRollback() throws Exception {
        AtomicReference<Statement> statement = new AtomicReference<>();
        AtomicReference<Handle> outer = new AtomicReference<>();
        var failure = new IllegalStateException("callback failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
            db.useHandle(outer::set);
            assertEquals(1, db.update("INSERT INTO entries VALUES (1, 10)", update -> update.execute()));
            try {
                db.update("UPDATE entries SET amount = 20 WHERE id = 1", update -> {
                    db.useHandle(handle -> assertSame(outer.get(), handle));
                    assertEquals(1, update.execute());
                    statement.set(update.getContext().getStatement());
                    throw failure;
                });
                fail("Expected callback failure");
            } catch (IllegalStateException caught) {
                assertSame(failure, caught);
                assertDoesNotThrow(() -> assertTrue(statement.get().isClosed()));
                assertFalse(outer.get().isClosed());
                assertTrue(outer.get().isInTransaction());
                assertEquals(20, db.query("SELECT amount FROM entries WHERE id = 1",
                        query -> query.mapTo(Integer.class).one()).intValue());
                throw caught;
            }
        })));

        assertTrue(outer.get().isClosed());
        assertEquals(0, count());
    }

    @Test
    void callbackFailureOutsideTransactionClosesStatementAndHandleWithoutAddingRollback() throws Exception {
        AtomicReference<Statement> statement = new AtomicReference<>();
        AtomicReference<Handle> handle = new AtomicReference<>();
        var failure = new IllegalArgumentException("callback failed after execution");

        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> db.update("INSERT INTO entries VALUES (1, 10)", update -> {
                    db.useHandle(handle::set);
                    update.execute();
                    statement.set(update.getContext().getStatement());
                    throw failure;
                })));

        assertTrue(statement.get().isClosed());
        assertTrue(handle.get().isClosed());
        assertEquals(1, count()); // JDBC autocommit, not an implicit helper transaction.
        assertEquals(1, db.update("DELETE FROM entries WHERE id = 1", update -> update.execute()));
    }

    private int count() {
        return db.query("SELECT COUNT(*) FROM entries", query -> query.mapTo(Integer.class).one());
    }
}
