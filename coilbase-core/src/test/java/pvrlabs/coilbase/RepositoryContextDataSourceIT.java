package pvrlabs.coilbase;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryContextDataSourceIT {
    private JdbcDataSource dataSource(TestInfo test) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:RepositoryContextDataSourceIT_" + test.getTestMethod().orElseThrow().getName()
                + "_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    @Test void constructionAndDialectSelection(TestInfo test) {
        var source = dataSource(test);
        var statement = SqlStatement.of("SELECT 1").h2("SELECT 2");
        assertEquals(1, RepositoryContext.create(source).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, RepositoryContext.create(source, Dialects.H2).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, RepositoryContext.create(source, " h2 ").query(statement, q -> q.mapTo(Integer.class).one()).intValue());
        assertEquals(2, RepositoryContext.create(Jdbi.create(source), Dialects.H2).query(statement, q -> q.mapTo(Integer.class).one()).intValue());
    }

    @Test void rejectsNullArgumentsAndBlankDialect(TestInfo test) {
        var source = dataSource(test);
        assertThrows(NullPointerException.class, () -> RepositoryContext.create((DataSource) null));
        assertThrows(NullPointerException.class, () -> RepositoryContext.create((DataSource) null, Dialects.H2));
        assertThrows(NullPointerException.class, () -> RepositoryContext.create((DataSource) null, "h2"));
        assertThrows(NullPointerException.class, () -> RepositoryContext.create(source, (Dialects) null));
        assertThrows(NullPointerException.class, () -> RepositoryContext.create(source, (String) null));
        assertThrows(IllegalArgumentException.class, () -> RepositoryContext.create(source, " "));
    }

    @Test void managedTransactionsJoinAndSeparateContextsRemainIndependent(TestInfo test) {
        var source = dataSource(test);
        var db = RepositoryContext.create(source);
        var other = RepositoryContext.create(source);
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
