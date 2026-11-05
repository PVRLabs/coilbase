package pvrlabs.coilbase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;

/** Compose this context into repositories. Share its Jdbi instance for transaction participation. */
public final class RepositoryContext {
    private final Jdbi jdbi;
    private final String dialect;
    private final RollbackAwareTransactions transactions;

    public RepositoryContext(Jdbi jdbi, Dialects dialect) {
        this(jdbi, Objects.requireNonNull(dialect, "dialect").name());
    }

    public RepositoryContext(Jdbi jdbi, String dialect) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.dialect = SqlStatement.dialectKey(dialect);
        this.transactions = RollbackAwareTransactions.install(jdbi);
    }

    /** Marks the current managed transaction; its eventual commit throws RollbackOnlyException. */
    public void markRollbackOnly() {
        if (jdbi.getTransactionHandler() != transactions) {
            throw new IllegalStateException("Jdbi transaction handler changed after context initialization");
        }
        var scope = jdbi.getHandleScope().get();
        if (scope == null) throw new IllegalStateException("No managed transaction on this thread");
        transactions.mark(scope.getHandle());
    }

    public String sql(SqlStatement statement) {
        return statement.sql(dialect);
    }

    /** Consume results inside the callback; the query closes before this method returns. */
    public <T> T query(String sql, Function<Query, T> callback) {
        return jdbi.withHandle(handle -> {
            try (var query = handle.createQuery(sql)) {
                return callback.apply(query);
            }
        });
    }

    public <T> T query(SqlStatement statement, Function<Query, T> callback) {
        return query(sql(statement), callback);
    }

    /** Bind and execute inside the callback; returns its affected-row count without adding a transaction. */
    public int update(String sql, Function<Update, Integer> callback) {
        return jdbi.withHandle(handle -> {
            try (var update = handle.createUpdate(sql)) {
                return callback.apply(update);
            }
        });
    }

    public int update(SqlStatement statement, Function<Update, Integer> callback) {
        return update(sql(statement), callback);
    }

    /**
     * Executes SQL text without starting a transaction. Joins an existing managed scope;
     * otherwise the datasource's JDBC autocommit policy applies. SQL failures propagate from Jdbi.
     */
    public void executeScript(String sql) {
        Objects.requireNonNull(sql, "sql");
        useHandle(handle -> {
            try (var script = handle.createScript(sql)) {
                script.execute();
            }
        });
    }

    /**
     * Reads a UTF-8 classpath resource and delegates to {@link #executeScript(String)}.
     * Paths are classpath-root-relative, with an optional leading slash. Uses the thread context
     * class loader, or this class's loader if none is set. Nothing is discovered or run automatically.
     *
     * @throws IllegalArgumentException if the resource is missing
     * @throws UncheckedIOException if reading or closing the resource fails
     */
    public void executeScriptResource(String resource) {
        Objects.requireNonNull(resource, "resource");
        var name = resource.startsWith("/") ? resource.substring(1) : resource;
        var loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = RepositoryContext.class.getClassLoader();
        String sql;
        try (var input = loader.getResourceAsStream(name)) {
            if (input == null) throw new IllegalArgumentException("Classpath SQL resource not found: " + resource);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read classpath SQL resource: " + resource, failure);
        }
        executeScript(sql);
    }

    public <T, X extends Exception> T withHandle(HandleCallback<T, X> callback) throws X {
        return jdbi.withHandle(callback);
    }

    public <X extends Exception> void useHandle(HandleConsumer<X> callback) throws X {
        jdbi.useHandle(callback);
    }

    public <T, X extends Exception> T inTransaction(HandleCallback<T, X> callback) throws X {
        return jdbi.inTransaction(callback);
    }

    /**
     * Provides convenience closer to Spring's {@code @Transactional} for operations
     * that do not need a handle, using an explicit callback instead of interception.
     * Joins an existing transaction or starts one; delegates transaction handling to Jdbi.
     */
    public void transactional(Runnable action) {
        Objects.requireNonNull(action, "action");
        useTransaction(ignored -> action.run());
    }

    public <X extends Exception> void useTransaction(HandleConsumer<X> callback) throws X {
        jdbi.useTransaction(callback);
    }
}
