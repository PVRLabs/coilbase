package consumer;

import org.junit.jupiter.api.Test;
import pvrlabs.coilbase.SqlStatement;
import pvrlabs.coilbase.RepositoryContext;
import org.h2.jdbcx.JdbcDataSource;
import static org.junit.jupiter.api.Assertions.*;

class CoreConsumerTest {
    @Test void coreApiIsUsableWithoutHttpOrJsonDependencies() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:consumer");
        var db = RepositoryContext.create(source);
        assertEquals(1, db.query(SqlStatement.of("SELECT 1"), q -> q.mapTo(Integer.class).one()).intValue());
        var loader = getClass().getClassLoader();
        for (var name : new String[]{"io.avaje.jex.Jex", "com.fasterxml.jackson.databind.ObjectMapper",
                "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule", "io.avaje.http.api.Controller"}) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(name), name);
        }
    }
}
