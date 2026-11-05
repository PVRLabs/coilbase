package consumer;

import org.junit.jupiter.api.Test;
import pvrlabs.coilbase.SqlStatement;
import static org.junit.jupiter.api.Assertions.*;

class CoreConsumerTest {
    @Test void coreApiIsUsableWithoutHttpOrJsonDependencies() throws Exception {
        assertNotNull(SqlStatement.of("SELECT 1"));
        var loader = getClass().getClassLoader();
        for (var name : new String[]{"io.avaje.jex.Jex", "com.fasterxml.jackson.databind.ObjectMapper",
                "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule", "io.avaje.http.api.Controller"}) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(name), name);
        }
    }
}
