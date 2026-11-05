package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.avaje.jex.Jex;
import io.avaje.jex.http.ExceptionHandler;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Optional Avaje Jex host helper: one catch-all error handler.
 *
 * <p>Jex wraps Jackson body-parsing failures, so a malformed or mismatched JSON body is
 * found by walking the cause chain and reported as 400. Every other failure is classified
 * by the application's own status function; 500 responses are logged with their cause.
 * The application keeps its own status codes and error-body shape.
 *
 * <p>Requires Jex and Jackson on the application classpath (both are transitive dependencies
 * of this artifact) and Java 21, like Jex itself. Not used by the persistence classes.
 *
 * <p>Nothing here wraps or replaces {@code Jex.create()}. Applications keep building Jex their
 * own way and may use any level: {@link #install} on a Jex they created, {@link #handler} to
 * register with their own {@code .error(...)} call, or only {@link #statusOf} inside a custom handler.
 */
public final class JexErrors {
    private static final System.Logger LOG = System.getLogger(JexErrors.class.getName());

    private JexErrors() {}

    /**
     * Registers {@link #handler} for all exceptions on a Jex the application created.
     *
     * @param status maps an application failure to an HTTP status; it is not asked about malformed JSON
     * @param body   builds the JSON error body for a status
     */
    public static Jex install(Jex jex, ToIntFunction<Throwable> status, IntFunction<?> body) {
        Objects.requireNonNull(jex, "jex");
        return jex.error(Exception.class, handler(status, body));
    }

    /** The catch-all handler itself, for applications that register it (or wrap it) themselves. */
    public static ExceptionHandler<Exception> handler(ToIntFunction<Throwable> status, IntFunction<?> body) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(body, "body");
        return (ctx, failure) -> {
            int code = statusOf(failure, status);
            if (code >= 500) LOG.log(System.Logger.Level.ERROR, "Request failed", failure);
            ctx.status(code).json(body.apply(code));
        };
    }

    /** 400 when the cause chain holds a malformed or mismatched JSON failure; otherwise the application's status. */
    public static int statusOf(Throwable failure, ToIntFunction<Throwable> status) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonParseException || cause instanceof MismatchedInputException) return 400;
        }
        return status.applyAsInt(failure);
    }
}
