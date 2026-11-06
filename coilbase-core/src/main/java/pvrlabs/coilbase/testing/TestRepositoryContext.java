package pvrlabs.coilbase.testing;

import java.util.function.Function;
import java.util.function.Supplier;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;
import pvrlabs.coilbase.RepositoryContext;
import pvrlabs.coilbase.SqlStatement;

/**
 * Executes handle-free transaction callbacks directly, without a database.
 * TODO: Move testing support to a separate coilbase-testing Maven module before the first
 * stable release, preserving this package and keeping it out of production dependencies.
 */
public class TestRepositoryContext implements RepositoryContext {
    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("TestRepositoryContext does not provide SQL or database handles");
    }

    public void markRollbackOnly() { throw unsupported(); }
    public String sql(SqlStatement statement) { throw unsupported(); }
    public <T> T query(String sql, Function<Query, T> callback) { throw unsupported(); }
    public <T> T query(SqlStatement statement, Function<Query, T> callback) { throw unsupported(); }
    public int update(String sql, Function<Update, Integer> callback) { throw unsupported(); }
    public int update(SqlStatement statement, Function<Update, Integer> callback) { throw unsupported(); }
    public void executeScript(String sql) { throw unsupported(); }
    public void executeScriptResource(String resource) { throw unsupported(); }
    public <T, X extends Exception> T withHandle(HandleCallback<T, X> callback) throws X { throw unsupported(); }
    public <X extends Exception> void useHandle(HandleConsumer<X> callback) throws X { throw unsupported(); }
    public <T, X extends Exception> T inTransaction(HandleCallback<T, X> callback) throws X { throw unsupported(); }
    public void transactional(Runnable action) { action.run(); }
    public <T> T transactional(Supplier<T> action) { return action.get(); }
    public <X extends Exception> void useTransaction(HandleConsumer<X> callback) throws X { throw unsupported(); }
}
