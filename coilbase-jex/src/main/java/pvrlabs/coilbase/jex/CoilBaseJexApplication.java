package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.avaje.jex.Jex;
import io.avaje.jex.http.HttpStatus;
import io.avaje.jex.http.NotFoundException;
import io.avaje.jex.core.json.JacksonJsonService;
import io.avaje.jex.spi.JsonService;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import pvrlabs.coilbase.Dialects;
import pvrlabs.coilbase.RepositoryContext;

/**
 * Optional base class that owns the lifecycle of a small Avaje Jex + CoilBase application.
 *
 * <p>{@link #start()} runs a fixed sequence with explicit datasource and route hooks:
 * <ol>
 *   <li>{@link #createPrimaryDataSource()} (default {@code null}: no database);</li>
 *   <li>if a datasource exists, create the primary {@link RepositoryContext}, available as {@link #db()};</li>
 *   <li>create Jex and configure JSON ({@link #objectMapper()}, {@link #jsonService()});</li>
 *   <li>install default error handling (customizable through {@link #configureErrorHandling()});</li>
 *   <li>{@link #registerRoutes(Jex)}: the application builds its services and registers controllers;</li>
 *   <li>register shutdown cleanup of the datasource this class owns;</li>
 *   <li>start Jex. Anything that fails before Jex is serving closes the owned datasource and is rethrown.</li>
 * </ol>
 *
 * <p>Explicit Java only: no container, scanning or auto-configuration. Subclasses may still use Jex directly
 * inside {@link #registerRoutes(Jex)} or by overriding the hooks. {@link JexErrors} stays usable on its own.
 * Requires Java 21, Jex and Jackson, like {@link JexErrors}.
 *
 * <p>Only the primary datasource returned by {@link #createPrimaryDataSource()} is owned: it is closed on
 * shutdown or startup failure if it implements {@link AutoCloseable}. H2's JdbcConnectionPool is also
 * supported through its public dispose() method, without requiring H2 on the library classpath.
 * Any other resource a subclass creates must be closed by the subclass.
 */
public abstract class CoilBaseJexApplication {
    private static final System.Logger LOG = System.getLogger(CoilBaseJexApplication.class.getName());
    private static final ErrorResponse INTERNAL_ERROR = new ErrorResponse(500, "internal_error");
    private static final ErrorResponse BAD_REQUEST = new ErrorResponse(400, "bad_request");
    private static final ErrorResponse NOT_FOUND = new ErrorResponse(404, "not_found");
    private final Map<Class<? extends Exception>, ErrorResponse> errorMappings = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private DataSource dataSource;
    private RepositoryContext db;
    private boolean monitoringEnabled;

    /** Enables process-local StatLite metrics. Call before starting the application. */
    public final CoilBaseJexApplication enableMonitoring() {
        if (started.get()) throw new IllegalStateException("Application already started");
        monitoringEnabled = true;
        return this;
    }

    /** Builds and runs the application on {@code PORT}, or 8080 when it is unset. Call once. */
    public final Jex.Server start() {
        return startWithDefaultPort(8080);
    }

    /**
     * Uses {@code PORT} when set and nonblank, otherwise the supplied default. Call once.
     * An invalid {@code PORT} throws {@link NumberFormatException} before startup is attempted.
     */
    public final Jex.Server startWithDefaultPort(int defaultPort) {
        String value = System.getenv("PORT");
        return start(value == null || value.isBlank() ? defaultPort : Integer.parseInt(value));
    }

    /**
     * Builds and runs the application on the supplied port. Call once, including after a startup
     * failure. Startup failures are rethrown; any cleanup failure is suppressed on the original.
     * @throws IllegalStateException if startup was already attempted
     */
    public final Jex.Server start(int port) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Application already started");
        try {
            dataSource = createPrimaryDataSource();
            if (dataSource != null) {
                var jdbi = Jdbi.create(dataSource);
                var dialect = primaryDialect();
                db = dialect == null ? new RepositoryContext(jdbi) : new RepositoryContext(jdbi, dialect);
            }
            var jex = Jex.create().jsonService(jsonService());
            configureMonitoringIfEnabled(jex);
            jex.port(port);
            jex.lifecycle().onShutdown(this::closeOwnedResources);
            return jex.start();
        } catch (RuntimeException | Error failure) {
            try {
                closeOwnedResources();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * The primary datasource, which this class then owns and closes. The default is no datasource.
     * Override {@link #primaryDialect()} only when dialect-specific SQL selection is needed.
     */
    protected DataSource createPrimaryDataSource() { return null; }

    /** SQL selection key for the primary context; defaults to null, selecting default SQL. */
    protected Dialects primaryDialect() { return null; }

    /** The primary repository context. Valid in {@link #registerRoutes(Jex)} and later. */
    protected final RepositoryContext db() {
        if (db == null) throw new IllegalStateException("No primary datasource: override createPrimaryDataSource()");
        return db;
    }

    /** Register routes and controllers; when a primary datasource is configured, build services from {@link #db()} here. */
    protected abstract void registerRoutes(Jex jex);

    /** Default: ISO-8601 dates, including Java time support via the bundled jackson-datatype-jsr310 dependency. */
    protected ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    protected JsonService jsonService() { return new JacksonJsonService(objectMapper()); }

    private void configureMonitoringIfEnabled(Jex jex) {
        // Keep StatLiteMetrics initialization behind the opt-in: disabled applications
        // should not need to load it, and monitoring may become an optional module.
        JexMonitoring monitoring = null;
        if (monitoringEnabled) {
            monitoring = new JexMonitoring();
            monitoring.install(jex);
        }
        installDefaultErrorHandling(jex);
        configureErrorHandling(jex);
        registerRoutes(jex);
        if (monitoring != null) monitoring.instrumentUnmatchedRequests(jex);
    }

    /** Register application exception mappings with {@link #mapError(Class, int, String)} before serving. */
    protected void configureErrorHandling() {}

    /**
     * Advanced hook for native Jex handlers; the safe default is already installed.
     * By default delegates to {@link #configureErrorHandling()}.
     * An override must call {@code super.configureErrorHandling(jex)} to invoke the no-argument hook.
     */
    protected void configureErrorHandling(Jex jex) { configureErrorHandling(); }

    /** Maps an exception to an error status and its standard HTTP code, or generic internal_error for 5xx. */
    protected final void mapError(Class<? extends Exception> type, int status) {
        mapError(type, status, defaultErrorCode(status));
    }

    /**
     * Registers an exception's status and JSON {"error": code} response during startup configuration.
     * The nearest mapped superclass wins, independent of registration order. If the thrown exception
     * has no mapping, its causes are checked in order. Registering the same type again replaces it.
     * @throws NullPointerException if type or code is null
     * @throws IllegalArgumentException if status is outside 400 through 599 or code is blank
     */
    protected final void mapError(Class<? extends Exception> type, int status, String code) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
        if (status < 400 || status > 599) throw new IllegalArgumentException("Error status must be 400 through 599");
        if (code.isBlank()) throw new IllegalArgumentException("Error code must not be blank");
        errorMappings.put(type, new ErrorResponse(status, code));
    }

    private static String defaultErrorCode(int status) {
        if (status >= 500) return "internal_error";
        for (var http : HttpStatus.values()) {
            if (http.status() == status) {
                var name = http.name();
                return name.substring(0, name.lastIndexOf('_')).toLowerCase(Locale.ROOT);
            }
        }
        return "request_error";
    }

    private void installDefaultErrorHandling(Jex jex) {
        jex.error(Exception.class, (ctx, failure) -> {
            var response = errorResponse(failure);
            if (response.status() >= 500) LOG.log(System.Logger.Level.ERROR, "Request failed", failure);
            ctx.status(response.status()).json(Map.of("error", response.code()));
        });
    }

    private ErrorResponse errorResponse(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
                var response = errorMappings.get(type);
                if (response != null) return response;
            }
        }
        if (failure instanceof NotFoundException) return NOT_FOUND;
        return JexErrors.statusOf(failure, ignored -> 500) == 400 ? BAD_REQUEST : INTERNAL_ERROR;
    }

    private record ErrorResponse(int status, String code) {}

    private void closeOwnedResources() {
        if (!closed.compareAndSet(false, true) || dataSource == null) return;
        try {
            if (dataSource instanceof AutoCloseable closeable) {
                closeable.close();
            } else if (dataSource.getClass().getName().equals("org.h2.jdbcx.JdbcConnectionPool")) {
                // Only this specific H2 pool is supported; there is no generic lifecycle discovery.
                try {
                    dataSource.getClass().getMethod("dispose").invoke(dataSource);
                } catch (InvocationTargetException failure) {
                    var cause = failure.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("Closing the primary datasource failed", cause);
                }
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Closing the primary datasource failed", failure);
        }
    }
}
