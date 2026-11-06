package pvrlabs.coilbase;

import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;

/**
 * SQL and transaction operations shared by repositories and services.
 *
 * <p>The Jdbi implementation joins nested transactions on the same thread and Jdbi instance;
 * nested transactions do not create savepoints or commit independently. Managed handles and
 * handle-backed results must not escape their callbacks. Applications own their Jdbi instances
 * and data sources. Implementations without a database may reject handle and SQL operations.
 */
public interface RepositoryContext {
    /** Creates a Jdbi-backed context using default SQL. */
    static RepositoryContext create(DataSource source) { return new JdbiRepositoryContext(source); }
    /** Creates a Jdbi-backed context using the given dialect. */
    static RepositoryContext create(DataSource source, Dialects dialect) { return new JdbiRepositoryContext(source, dialect); }
    /** Creates a Jdbi-backed context using a case-insensitive dialect key. */
    static RepositoryContext create(DataSource source, String dialect) { return new JdbiRepositoryContext(source, dialect); }
    /** Creates a context over the application-owned Jdbi instance using default SQL. */
    static RepositoryContext create(Jdbi jdbi) { return new JdbiRepositoryContext(jdbi); }
    /** Creates a context over the application-owned Jdbi instance using the given dialect. */
    static RepositoryContext create(Jdbi jdbi, Dialects dialect) { return new JdbiRepositoryContext(jdbi, dialect); }
    /** Creates a context over the application-owned Jdbi instance using a dialect key. */
    static RepositoryContext create(Jdbi jdbi, String dialect) { return new JdbiRepositoryContext(jdbi, dialect); }

    /** Marks the current managed transaction for rollback; its outermost commit fails with
     * {@link RollbackOnlyException}. Throws if no managed transaction is active. */
    void markRollbackOnly();
    /** Selects the SQL text for the configured dialect, falling back to default SQL. */
    String sql(SqlStatement statement);
    /** Runs a query callback; consume results inside it because the query closes on return. */
    <T> T query(String sql, Function<Query, T> callback);
    /** Selects dialect SQL and runs a query callback, closing the query on return. */
    <T> T query(SqlStatement statement, Function<Query, T> callback);
    /** Runs an update callback and returns its integer result; the update closes on return. */
    int update(String sql, Function<Update, Integer> callback);
    /** Selects dialect SQL and runs an update callback, closing the update on return. */
    int update(SqlStatement statement, Function<Update, Integer> callback);
    /** Executes SQL without starting a transaction, joining any existing managed scope. */
    void executeScript(String sql);
    /** Reads a UTF-8 classpath resource and executes it as a script. Paths are relative to
     * the classpath root and may begin with a slash. */
    void executeScriptResource(String resource);
    /** Runs a managed handle callback. Do not close or retain the handle or its results. */
    <T, X extends Exception> T withHandle(HandleCallback<T, X> callback) throws X;
    /** Void form of {@link #withHandle(HandleCallback)} with the same handle lifetime. */
    <X extends Exception> void useHandle(HandleConsumer<X> callback) throws X;
    /** Runs a managed transaction callback or joins the current transaction. The handle is
     * scoped to the callback; a rollback-only outer transaction fails when commit is attempted. */
    <T, X extends Exception> T inTransaction(HandleCallback<T, X> callback) throws X;
    /** Runs handle-free work in a managed transaction, joining an existing transaction. */
    void transactional(Runnable action);
    /** Runs value-returning handle-free work in a managed transaction, joining an existing one. */
    <T> T transactional(Supplier<T> action);
    /** Void form of {@link #inTransaction(HandleCallback)} with the same transaction rules. */
    <X extends Exception> void useTransaction(HandleConsumer<X> callback) throws X;
}
