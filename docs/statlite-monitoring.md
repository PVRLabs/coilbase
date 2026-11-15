# StatLite Monitoring

CoilBase Jex applications can expose basic monitoring with a single
`enableMonitoring()` call. The endpoint reports:

- HTTP request and error counts and request duration.
- JVM heap usage and uptime.
- Automatic primary database health as `UP` or `DOWN`, when a primary
  datasource is configured.

No separate database-health configuration is needed. Monitoring is opt-in and
the database status is omitted when the application has no primary datasource.

[StatLite](https://github.com/PVRLabs/statlite) is the lightweight backend this
integration feeds: a self-hosted single Go binary with embedded SQLite storage
that polls the application, stores measurements, and serves a dashboard. It
gives small deployments basic metrics and health visibility without operating a
Prometheus and Grafana stack.

![StatLite dashboard showing request traffic, latency, and JVM metrics over
time](assets/statlite-dashboard.webp)

*Example dashboard from the StatLite project, fed by a sample application.*

This experimental integration may change. `coilbase-core` includes
`pvrlabs.coilbase.monitoring.StatLiteMetrics`, a process-local, opt-in helper
for the
[StatLite Metrics v1 contract](https://github.com/PVRLabs/statlite/blob/main/docs/statlite-metrics-v1.md).
It has no HTTP, JSON, or StatLite runtime dependency. Create one instance per
application process, record each completed application request once, and serve
its snapshot using the application's JSON mapper. These examples were checked
with Jex 3.6 and Javalin 7.2.3; adapt the wiring to the versions and error
handlers in your application. Jex requires Java 21.

## Avaje Jex

Call `enableMonitoring()` on a `CoilBaseJexApplication` before starting it:

```java
import io.avaje.jex.Jex;
import pvrlabs.coilbase.jex.CoilBaseJexApplication;

class Application extends CoilBaseJexApplication {
    @Override protected void registerRoutes(Jex jex) {
        jex.get("/hello", ctx -> ctx.text("hello"));
    }

    public static void main(String[] args) {
        new Application().enableMonitoring().start();
    }
}
```

Monitoring is disabled by default. Enabling it creates one helper and registers
`GET /statlite/metrics`, using the application's JSON service. It reports `UP`
as a fixed application status, not a readiness or database check. Normal error
handlers and mappings remain in effect. Unmatched routes return 404 by default;
applications can override this with their own error mappings. Monitoring records
the actual response status, whichever mapping the application chooses.

The example above does not configure a primary datasource, so its response omits
`database_status`. When a primary datasource is configured, monitoring reports
its health automatically, with no additional configuration. A background check
runs initially and then 60 seconds after each completed check; scrapes use the
cached result. The field is omitted until the first check completes; failures
report `DOWN` until a later check succeeds. Application status remains independent.
The sampler stops on shutdown; a JDBC driver that ignores interruption may take
longer to finish an in-progress connection or query.

The two-second SQL query timeout does not bound connection acquisition. Configure
the datasource pool and JDBC driver connection timeouts for your deployment; for
example, HikariCP's `connectionTimeout` limits how long a caller waits for a pool
connection. CoilBase accepts application-provided datasources and cannot enforce
their timeout behavior. If acquisition or a query never returns, the single
sampler cannot refresh again and the last result can remain stale indefinitely.

Jex 3.6 bypasses filters for unmatched routes. Monitoring delegates those errors
to the handler Jex would otherwise select and counts the response with zero
duration because no supported filter ran before routing. Total duration and
average latency therefore underestimate unmatched requests. Matched route
exceptions are counted by the filter after Jex handles them, without double
counting. Exceptions escaping a custom filter or error handler can be mapped
later and may not be represented accurately. The filter measures request
processing, not necessarily the full lifetime of a streaming response; counters
may update just after a client receives its response.

## Javalin

Javalin's HTTP request logger receives the final status and elapsed milliseconds,
including unmatched routes and mapped errors. Configure an existing JSON mapper
such as Jackson for `ctx.json`. If the application already has a request logger,
combine this logic with it rather than replacing its logging.

```java
import io.javalin.Javalin;
import pvrlabs.coilbase.monitoring.StatLiteMetrics;

StatLiteMetrics metrics = new StatLiteMetrics();
Javalin app = Javalin.create(config -> {
    config.requestLogger.http((ctx, elapsedMs) -> {
        if (!ctx.path().equals(StatLiteMetrics.PATH)) {
            metrics.recordRequest(ctx.status().getCode(), (long) (elapsedMs * 1_000_000));
        }
    });
    config.routes.get(StatLiteMetrics.PATH, ctx -> {
        ctx.header("Cache-Control", "no-store");
        ctx.json(metrics.snapshot("UP", null));
    });
    // Register application routes and error handlers here.
});
// app.start(8080);
```

The logger's timing is Javalin's request-processing time and has millisecond
precision in the tested version. Counters may update just after the client
receives the response. Verify timing and completion behavior if using streaming
or asynchronous responses.

## Payload and polling

The snapshot contains `schema`, `integration`, application `status`, JVM
`started_at`, and `metrics`. The latter contains cumulative request, 404, 4xx
(including 404), and 5xx counts, cumulative request duration in seconds, JVM
heap used in bytes, and JVM uptime in seconds. Heap is not process RSS. The
helper does not report process CPU or host metrics. Counters reset with the
helper instance; create only one for the process lifetime. `started_at` is the
JVM start, so replacing the helper during a running JVM resets counters without
changing that identity. Use an authoritative, inexpensive application status
in place of the example's constant `UP`; it is not proof of dependency health.
The null second argument in the manual example omits database status.

## Manual database health checks for Core and other integrations

`pvrlabs.coilbase.monitoring.DatabaseHealth` provides an independent, explicit
probe for Core and other integrations. CoilBase Jex applications using
`enableMonitoring()` do not need to wire this probe. For example, with an
existing Jdbi instance:

```java
import pvrlabs.coilbase.monitoring.DatabaseHealth;

DatabaseHealth result = DatabaseHealth.check(jdbi, 2); // query timeout in seconds
boolean healthy = result.healthy();
// result.checkedAt() and result.elapsedMillis() describe this check.
```

Each call opens a separate handle and runs `SELECT 1`, which is not portable to
every database dialect; it does not join a
managed business transaction. A runtime failure returns an unhealthy result
without exposing the exception. The timeout must be positive and is passed to
the JDBC query; connection acquisition and driver behavior may take longer.
Outside the Jex integration, the application decides when to invoke it and how to
cache or use the result. Avoid running it on each metrics request. Jex uses a
two-second query timeout, which does not bound connection acquisition.

## Configure StatLite

Configure a StatLite target for each process, using its actual address:

```yaml
targets:
  - name: coilbase-app
    type: statlite-metrics
    url: http://127.0.0.1:8080/statlite/metrics
```

Keep the endpoint on a private interface or restrict it at the network or proxy;
StatLite's target does not send credentials. Avoid proxy caching and do not poll
a load-balanced URL across independent processes. The examples exclude the
metrics path for all methods, including failed requests to that path. If a proxy
or application uses a different path, update the exclusion as well.

The [Jex tests](../coilbase-jex/src/test/java/pvrlabs/coilbase/jex/MonitoringIT.java)
exercise the base application over HTTP. The [verification fixture](../verification/metrics/src/test/java/pvrlabs/coilbase/verification/MetricsExamplesTest.java)
also exercises the manual Javalin example over HTTP. From the repository root, install Core with
`mvn -pl coilbase-core -am -DskipTests install`, then run
`mvn -f verification/metrics/pom.xml test`. The fixture is not a library module.

The optional slow suite also tests collection by StatLite using a temporary
SQLite database. To run it locally, provide the executable and run
`STATLITE_BIN=/path/to/statlite mvn -Pslow-tests verify`. The regular Maven suite
does not require StatLite; CI provisions it only for the slow-tests job.

## Other monitoring integrations

StatLite is a lightweight, opt-in integration. See
[CoilBase vs Spring Boot](comparison-with-spring-boot.md) for the broader scope.
Broader observability support, such as Micrometer or Prometheus-compatible
metrics, is a potential future addition, prioritized by demand. If you need a
specific integration, open an issue describing your use case.
