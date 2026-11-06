package pvrlabs.coilbase.example.hello;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pvrlabs.coilbase.testing.TestRepositoryContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GreetingServiceTest {
    @Mock GreetingRepository greetings;
    private GreetingService service;

    @BeforeEach void setUp() { service = new GreetingService(new TestRepositoryContext(), greetings); }

    /** Uses a mock to verify that name normalization produces the greeting passed to persistence. */
    @Test void trimsNameAndStoresGreeting() {
        var greeting = service.greet("  Ada  ");
        assertEquals("Hello, Ada!", greeting.message());
        assertFalse(greeting.id().isBlank());
        verify(greetings).insert(greeting);
    }

    /** Tests validation in isolation and verifies that rejected input never reaches persistence. */
    @Test void rejectsInvalidNamesWithoutCallingRepository() {
        for (var name : new String[]{null, "", "  ", "a".repeat(81)}) {
            assertThrows(GreetingService.InvalidNameException.class, () -> service.greet(name));
        }
        verifyNoInteractions(greetings);
    }

    /** Stubs a repository result and checks that the service returns it without needing a database. */
    @Test void listsStoredGreetings() {
        var stored = List.of(new Greeting("1", "Hello, Ada!"));
        when(greetings.findAll()).thenReturn(stored);
        assertEquals(stored, service.findAll());
        verify(greetings).findAll();
    }
}
