package pvrlabs.coilbase.testing;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestRepositoryContextTest {
    private final TestRepositoryContext db = new TestRepositoryContext();

    @Test void callbacksRunOnceAndReturnValuesIncludingWhenNested() {
        var calls = new AtomicInteger();
        db.transactional((Runnable) calls::incrementAndGet);
        assertEquals("result", db.transactional(() -> {
            calls.incrementAndGet();
            return db.transactional(() -> { calls.incrementAndGet(); return "result"; });
        }));
        assertEquals(3, calls.get());
    }

    @Test void callbackExceptionsPropagateUnchanged() {
        var failure = new IllegalStateException("failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> db.transactional((Runnable) () -> { throw failure; })));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> db.transactional(() -> { throw failure; })));
    }

    @Test void sqlAndHandleOperationsFailExplicitly() {
        assertThrows(UnsupportedOperationException.class, () -> db.query("select 1", q -> null));
        assertThrows(UnsupportedOperationException.class, () -> db.update("update x", u -> 1));
        assertThrows(UnsupportedOperationException.class, () -> db.executeScript("select 1"));
        assertThrows(UnsupportedOperationException.class, () -> db.executeScriptResource("schema.sql"));
        assertThrows(UnsupportedOperationException.class, () -> db.withHandle(h -> null));
        assertThrows(UnsupportedOperationException.class, () -> db.useHandle(h -> {}));
        assertThrows(UnsupportedOperationException.class, () -> db.inTransaction(h -> null));
        assertThrows(UnsupportedOperationException.class, () -> db.useTransaction(h -> {}));
        assertThrows(UnsupportedOperationException.class, db::markRollbackOnly);
    }
}
