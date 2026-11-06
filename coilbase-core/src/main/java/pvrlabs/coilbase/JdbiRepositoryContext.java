package pvrlabs.coilbase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;

/**
 * Compose this context into repositories. Within an active managed transaction, callbacks on the
 * same thread and Jdbi instance share its handle and transaction; separate instances and threads
 * do not propagate transactions.
 * Nested transactions join the current transaction, without a savepoint or independent commit.
 * Create contexts before opening managed handles. Construction installs a rollback-aware wrapper
 * around Jdbi's default local transaction handler; custom transaction handlers are unsupported.
 * The application owns the Jdbi instance and its datasource.
 */
public final class JdbiRepositoryContext implements RepositoryContext {
    private final Jdbi jdbi;
    private final String dialect;
    private final RollbackAwareTransactions transactions;

    /** Creates a context for the application-owned datasource using default SQL.
     * @throws NullPointerException if dataSource is null
     */
    public JdbiRepositoryContext(DataSource dataSource) {
        this(Jdbi.create(Objects.requireNonNull(dataSource, "dataSource")));
    }

    /** Creates a context for the application-owned datasource using an enum SQL selection key.
     * @throws NullPointerException if an argument is null
     */
    public JdbiRepositoryContext(DataSource dataSource, Dialects dialect) {
        this(Jdbi.create(Objects.requireNonNull(dataSource, "dataSource")), dialect);
    }

    /** Creates a context for the application-owned datasource using a trimmed, case-insensitive SQL selection key.
     * @throws IllegalArgumentException if the dialect is blank
     * @throws NullPointerException if an argument is null
     */
    public JdbiRepositoryContext(DataSource dataSource, String dialect) {
        this(Jdbi.create(Objects.requireNonNull(dataSource, "dataSource")), dialect);
    }

    /**
     * Creates a context that selects default SQL without a dialect.
     * @throws IllegalArgumentException if the Jdbi transaction handler is unsupported
     * @throws IllegalStateException if a managed handle is open on this thread
     * @throws NullPointerException if jdbi is null
     */
    public JdbiRepositoryContext(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.dialect = null;
        this.transactions = RollbackAwareTransactions.install(jdbi);
    }

    /**
     * Creates a context using an enum SQL selection key.
     * @throws IllegalArgumentException if the Jdbi transaction handler is unsupported
     * @throws IllegalStateException if a managed handle is open on this thread
     * @throws NullPointerException if an argument is null
     */
    public JdbiRepositoryContext(Jdbi jdbi, Dialects dialect) {
        this(jdbi, Objects.requireNonNull(dialect, "dialect").name());
    }

    /**
     * Creates a context using a trimmed, case-insensitive SQL selection key.
     * @throws IllegalArgumentException if the dialect is blank or the transaction handler is unsupported
     * @throws IllegalStateException if a managed handle is open on this thread
     * @throws NullPointerException if an argument is null
     */
    public JdbiRepositoryContext(Jdbi jdbi, String dialect) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.dialect = SqlStatement.dialectKey(dialect);
        this.transactions = RollbackAwareTransactions.install(jdbi);
    }

    /**
     * Marks the current managed transaction; its eventual commit throws RollbackOnlyException.
     * Use this when a failure is caught inside a transaction that must still roll back.
     *
     * @throws IllegalStateException if there is no active managed transaction on this thread,
     *         or if the Jdbi transaction handler has changed
     */
    public void markRollbackOnly() {
        if (jdbi.getTransactionHandler() != transactions) {
            throw new IllegalStateException("Jdbi transaction handler changed after context initialization");
        }
        var scope = jdbi.getHandleScope().get();
        if (scope == null) throw new IllegalStateException("No managed transaction on this thread");
        transactions.mark(scope.getHandle());
    }

    public String sql(SqlStatement statement) {
        return dialect == null ? statement.sql() : statement.sql(dialect);
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

    /**
     * Bind and execute inside the callback; returns the callback's integer result without adding a
     * transaction. The callback must execute the update and return its affected-row count if that
     * is desired. The update closes before this method returns; a null result cannot be unboxed.
     */
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

    /**
     * Delegates to Jdbi's managed handle callback. Consume handle-backed results inside the callback;
     * do not close or retain the managed handle. Callback exceptions propagate according to Jdbi.
     */
    public <T, X extends Exception> T withHandle(HandleCallback<T, X> callback) throws X {
        return jdbi.withHandle(callback);
    }

    /** Void form of {@link #withHandle(HandleCallback)}, with the same handle lifetime. */
    public <X extends Exception> void useHandle(HandleConsumer<X> callback) throws X {
        jdbi.useHandle(callback);
    }

    /**
     * Runs a managed transaction or joins the current one. The handle is scoped to the callback;
     * callback exceptions and transaction failures propagate according to Jdbi. A rollback-only
     * transaction throws {@link RollbackOnlyException} when its outermost commit is attempted.
     */
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

    /** Runs value-returning work in a managed transaction, joining an existing transaction. */
    public <T> T transactional(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return inTransaction(ignored -> action.get());
    }

    /** Void form of {@link #inTransaction(HandleCallback)}, with the same transaction rules. */
    public <X extends Exception> void useTransaction(HandleConsumer<X> callback) throws X {
        jdbi.useTransaction(callback);
    }
}
