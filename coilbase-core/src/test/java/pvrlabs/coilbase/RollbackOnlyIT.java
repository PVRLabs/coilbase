package pvrlabs.coilbase;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import static org.junit.jupiter.api.Assertions.*;

class RollbackOnlyIT {
    @Test void nativeOuterBoundaryRollsBackExplicitMarkerDespiteCaughtInnerFailure(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var rows = RepositoryIT.rows(jdbi);
        var second = RepositoryContext.create(jdbi, Dialects.H2);
        assertThrows(RollbackOnlyException.class, () -> jdbi.useTransaction(outer -> {
            rows.add(1);
            assertThrows(IOException.class, () -> second.useTransaction(inner -> {
                rows.add(2);
                second.markRollbackOnly();
                second.markRollbackOnly(); // idempotent
                throw new IOException("caught");
            }));
            rows.add(3); // marker does not end transaction prematurely
            assertEquals(3, rows.count());
            assertTrue(outer.isInTransaction());
        }));
        assertEquals(0, rows.count());
    }

    @Test void sameHandleCanStartCleanTransactionAfterMarkedRollback(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var rows = RepositoryIT.rows(jdbi);
        jdbi.useHandle(h -> {
            assertThrows(RollbackOnlyException.class, () -> rows.db.inTransaction(tx -> {
                rows.add(1);
                rows.db.markRollbackOnly();
                return "must not return";
            }));
            assertFalse(h.isInTransaction());
            rows.db.useTransaction(tx -> rows.add(2));
        });
        assertEquals(1, rows.count());
    }

    @Test void markerClearsWhenOriginalExceptionEscapes(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var rows = RepositoryIT.rows(jdbi);
        jdbi.useHandle(h -> {
            assertThrows(IOException.class, () -> rows.db.useTransaction(tx -> {
                rows.add(1);
                rows.db.markRollbackOnly();
                throw new IOException("preserve original failure");
            }));
            rows.db.useTransaction(tx -> rows.add(2));
        });
        assertEquals(1, rows.count());
    }

    @Test void noTransactionFailsWithoutCreatingManagedScope(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var db = RepositoryContext.create(jdbi, Dialects.H2);
        assertThrows(IllegalStateException.class, db::markRollbackOnly);
        assertNull(jdbi.getHandleScope().get());
        db.useHandle(h -> assertThrows(IllegalStateException.class, db::markRollbackOnly));
    }

    @Test void nativeIsolationOverloadHonorsMarker(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var rows = RepositoryIT.rows(jdbi);
        assertThrows(RollbackOnlyException.class, () -> jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, h -> {
            rows.add(1);
            rows.db.markRollbackOnly();
            return 1;
        }));
        assertEquals(0, rows.count());
    }

    @Test void markerDoesNotAffectAnotherThreadOrInstance(TestInfo test) throws Exception {
        var a = RepositoryIT.rows(RepositoryIT.fresh(test, "first"));
        var b = RepositoryIT.rows(RepositoryIT.fresh(test, "second"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertThrows(RollbackOnlyException.class, () -> a.db.useTransaction(h -> {
                a.add(1);
                a.db.markRollbackOnly();
                b.db.useTransaction(other -> b.add(1));
                executor.submit(() -> {
                    assertThrows(IllegalStateException.class, a.db::markRollbackOnly);
                    a.db.useTransaction(other -> a.add(2));
                }).get(5, TimeUnit.SECONDS);
            }));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, a.count());
        assertEquals(1, b.count());
    }

    @Test void lateInstallationAndCustomHandlersAreRejected(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        jdbi.useHandle(h -> assertThrows(IllegalStateException.class, () -> RepositoryContext.create(jdbi, Dialects.H2)));
        jdbi.setTransactionHandler(new org.jdbi.v3.core.transaction.RollbackOnlyTransactionHandler());
        assertThrows(IllegalArgumentException.class, () -> RepositoryContext.create(jdbi, Dialects.H2));
    }

    @Test void attemptedExplicitCommitCannotBypassMarkerAndRollbackCallbacksRun(TestInfo test) {
        var jdbi = RepositoryIT.fresh(test, "main");
        var rows = RepositoryIT.rows(jdbi);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var rolledBack = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(RollbackOnlyException.class, () -> rows.db.useTransaction(h -> {
            h.afterCommit(() -> committed.set(true));
            h.afterRollback(() -> rolledBack.set(true));
            rows.add(1);
            rows.db.markRollbackOnly();
            assertThrows(RollbackOnlyException.class, h::commit);
        }));
        assertFalse(committed.get());
        assertTrue(rolledBack.get());
        assertEquals(0, rows.count());
    }

}
