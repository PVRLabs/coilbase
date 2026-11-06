package pvrlabs.coilbase.jex;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JexErrorsTest {
    @Test void statusOfFindsWrappedJacksonFailure() {
        var wrapped = new RuntimeException("wrapper", new JsonParseException(null, "bad"));
        assertEquals(400, JexErrors.statusOf(wrapped, failure -> 500));
        assertEquals(500, JexErrors.statusOf(new IllegalStateException(), failure -> 500));
    }
}
