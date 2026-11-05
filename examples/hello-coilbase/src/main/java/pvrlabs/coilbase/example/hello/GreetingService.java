package pvrlabs.coilbase.example.hello;

import java.util.List;
import java.util.UUID;
import pvrlabs.coilbase.RepositoryContext;

/** Validates names and creates greetings within an explicit transaction, delegating SQL to the repository. */
public final class GreetingService {
    private final RepositoryContext db;
    private final GreetingRepository greetings;

    public GreetingService(RepositoryContext db, GreetingRepository greetings) {
        this.db = db;
        this.greetings = greetings;
    }

    public Greeting greet(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            throw new InvalidNameException();
        }
        return db.transactional(() -> {
            var greeting = new Greeting(UUID.randomUUID().toString(), "Hello, " + name.strip() + "!");
            greetings.insert(greeting);
            return greeting;
        });
    }

    public List<Greeting> findAll() { return greetings.findAll(); }

    /** Signals invalid input, mapped by the application to a 400 JSON response. */
    public static final class InvalidNameException extends RuntimeException {}
}
