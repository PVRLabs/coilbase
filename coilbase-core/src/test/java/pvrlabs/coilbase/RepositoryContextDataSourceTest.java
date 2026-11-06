package pvrlabs.coilbase;

import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryContextDataSourceTest {
    private JdbcDataSource dataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    @Test void constructionAndDialectSelection() {
        var source = dataSource();
        var statement = SqlStatement.of("SELECT 1").h2("SELECT 2");
        assertEquals(1, new RepositoryContext(source).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, new RepositoryContext(source, Dialects.H2).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, new RepositoryContext(source, " h2 ").query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, new RepositoryContext(Jdbi.create(source), Dialects.H2).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
    }

    @Test void rejectsNullArgumentsAndBlankDialect() {
        var source = dataSource();
        assertThrows(NullPointerException.class, () -> new RepositoryContext((DataSource) null));
        assertThrows(NullPointerException.class, () -> new RepositoryContext((DataSource) null, Dialects.H2));
        assertThrows(NullPointerException.class, () -> new RepositoryContext((DataSource) null, "h2"));
        assertThrows(NullPointerException.class, () -> new RepositoryContext(source, (Dialects) null));
        assertThrows(NullPointerException.class, () -> new RepositoryContext(source, (String) null));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryContext(source, " "));
    }

    @Test void managedTransactionsJoinAndSeparateContextsRemainIndependent() {
        var source = dataSource();
        var db = new RepositoryContext(source);
        var other = new RepositoryContext(source);
        db.executeScript("CREATE TABLE entries(id INT PRIMARY KEY)");
        assertThrows(RollbackOnlyException.class, () -> db.transactional(() -> {
            db.update("INSERT INTO entries VALUES (1)", u -> u.execute());
            db.transactional(() -> {
                db.update("INSERT INTO entries VALUES (2)", u -> u.execute());
                db.markRollbackOnly();
            });
            other.transactional(() -> other.update("INSERT INTO entries VALUES (3)", u -> u.execute()));
        }));
        assertEquals(java.util.List.of(3), db.query("SELECT id FROM entries", q -> q.mapTo(Integer.class).list()));
        assertThrows(IllegalStateException.class, () -> db.transactional(() -> {
            db.update("INSERT INTO entries VALUES (4)", u -> u.execute());
            throw new IllegalStateException("rollback");
        }));
        assertEquals(java.util.List.of(3), db.query("SELECT id FROM entries", q -> q.mapTo(Integer.class).list()));
    }
}
