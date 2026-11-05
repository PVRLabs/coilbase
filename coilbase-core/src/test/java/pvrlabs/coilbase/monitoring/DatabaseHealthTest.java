package pvrlabs.coilbase.monitoring;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseHealthTest {
    @Test void reportsSuccessAndFailureWithoutExposingExceptions() {
        var success = DatabaseHealth.check(Jdbi.create("jdbc:h2:mem:health"), 1);
        assertTrue(success.healthy());
        assertNotNull(success.checkedAt());
        assertTrue(success.elapsedMillis() >= 0);
        var failure = DatabaseHealth.check(Jdbi.create("jdbc:invalid:health"), 1);
        assertFalse(failure.healthy());
        assertNotNull(failure.checkedAt());
        assertTrue(failure.elapsedMillis() >= 0);
    }

    @Test void validatesArguments() {
        assertThrows(NullPointerException.class, () -> DatabaseHealth.check(null, 1));
        assertThrows(IllegalArgumentException.class, () -> DatabaseHealth.check(Jdbi.create("jdbc:h2:mem:health"), 0));
    }
}
