package pvrlabs.coilbase.jex;

import io.avaje.jex.Jex;
import io.avaje.jex.http.ExceptionHandler;
import io.avaje.jex.http.NotFoundException;
import java.util.Objects;
import pvrlabs.coilbase.monitoring.StatLiteMetrics;

final class JexMonitoring {
    private static final String MONITORED_REQUEST = CoilBaseJexApplication.class.getName() + ".monitored";
    private final StatLiteMetrics metrics = new StatLiteMetrics();

    void install(Jex jex) {
        jex.filter((ctx, chain) -> {
            if (ctx.path().equals(StatLiteMetrics.PATH)) { chain.proceed(); return; }
            ctx.attribute(MONITORED_REQUEST, Boolean.TRUE);
            long start = System.nanoTime();
            try { chain.proceed(); }
            finally {
                int status = ctx.exchange().getResponseCode();
                metrics.recordRequest(status > 0 ? status : 204, System.nanoTime() - start);
            }
        });
        jex.get(StatLiteMetrics.PATH, ctx -> {
            ctx.header("Cache-Control", "no-store");
            ctx.json(metrics.snapshot("UP", null));
        });
    }

    // Jex 3.6 skips filters when no route matches. Delegate to the same handler
    // Jex would select, preserving application error mappings and response bodies.
    @SuppressWarnings("unchecked")
    void instrumentUnmatchedRequests(Jex jex) {
        ExceptionHandler<Exception> handler = null;
        for (Class<?> type = NotFoundException.class; type != null; type = type.getSuperclass()) {
            var candidate = jex.routing().errorHandlers().get(type);
            if (candidate != null) { handler = (ExceptionHandler<Exception>) candidate; break; }
        }
        var delegate = Objects.requireNonNull(handler, "NotFoundException handler");
        jex.error(NotFoundException.class, (ctx, failure) -> {
            if (ctx.attribute(MONITORED_REQUEST) != null || ctx.path().equals(StatLiteMetrics.PATH)) {
                delegate.handle(ctx, failure);
                return;
            }
            try { delegate.handle(ctx, failure); }
            finally {
                int status = ctx.exchange().getResponseCode();
                if (status <= 0) status = ctx.status();
                metrics.recordRequest(status >= 100 && status <= 599 ? status : 500, 0);
            }
        });
    }
}
