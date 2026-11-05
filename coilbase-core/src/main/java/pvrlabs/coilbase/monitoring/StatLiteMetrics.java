package pvrlabs.coilbase.monitoring;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-local StatLite Metrics v1 snapshots, independent of HTTP framework and JSON mapper.
 * Optional helper; applications may use their existing monitoring integration instead.
 */
public final class StatLiteMetrics {
    public static final String PATH = "/statlite/metrics";
    private long requests, notFound, clientErrors, serverErrors, durationNanos;

    /** Call once after completion. Do not instrument the metrics endpoint itself. */
    public synchronized void recordRequest(int status, long elapsedNanos) {
        if (status < 100 || status > 599 || elapsedNanos < 0) {
            throw new IllegalArgumentException("Invalid HTTP status or duration");
        }
        requests++;
        if (status == 404) notFound++;
        if (status >= 400 && status < 500) clientErrors++;
        if (status >= 500) serverErrors++;
        durationNanos += elapsedNanos;
    }

    /** databaseStatus must be authoritative/cached; null omits it. Never probes the database. */
    public synchronized Map<String, Object> snapshot(String status, String databaseStatus) {
        if (status == null || status.isBlank() || (databaseStatus != null && databaseStatus.isBlank())) {
            throw new IllegalArgumentException("Status must not be blank");
        }
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("requests_total", requests);
        metrics.put("responses_404_total", notFound);
        metrics.put("responses_4xx_total", clientErrors);
        metrics.put("responses_5xx_total", serverErrors);
        metrics.put("request_duration_seconds_total", durationNanos / 1_000_000_000.0);
        var runtime = ManagementFactory.getRuntimeMXBean();
        metrics.put("uptime_seconds", runtime.getUptime() / 1000.0);
        metrics.put("runtime_heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        var body = new LinkedHashMap<String, Object>();
        body.put("schema", "statlite-metrics/v1");
        body.put("integration", "coilbase");
        body.put("status", status);
        if (databaseStatus != null) body.put("database_status", databaseStatus);
        body.put("started_at", Instant.ofEpochMilli(runtime.getStartTime()).toString());
        body.put("metrics", Map.copyOf(metrics));
        return Map.copyOf(body);
    }
}
