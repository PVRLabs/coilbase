package pvrlabs.coilbase.monitoring;

import java.time.Instant;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/**
 * An explicit probe result. Cache/refresh outside the metrics request path.
 * Optional helper; applications control probe scheduling and may use their existing monitoring integration.
 */
public record DatabaseHealth(boolean healthy, long elapsedMillis, Instant checkedAt) {
    public static DatabaseHealth check(Jdbi jdbi, int queryTimeoutSeconds) {
        Objects.requireNonNull(jdbi, "jdbi");
        if (queryTimeoutSeconds < 1) throw new IllegalArgumentException("timeout must be positive");
        long start = System.nanoTime();
        boolean healthy;
        // open() deliberately bypasses managed scope; this must not join a business transaction.
        try (var handle = jdbi.open()) {
            healthy = handle.createQuery("SELECT 1").setQueryTimeout(queryTimeoutSeconds)
                    .mapTo(Integer.class).one() == 1;
        } catch (RuntimeException failure) {
            healthy = false; // No credentials, JDBC URLs or exception messages in the public result.
        }
        return new DatabaseHealth(healthy, (System.nanoTime() - start) / 1_000_000, Instant.now());
    }
}
