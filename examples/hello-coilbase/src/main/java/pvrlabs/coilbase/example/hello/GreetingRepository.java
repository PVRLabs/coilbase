package pvrlabs.coilbase.example.hello;

import java.util.List;
import pvrlabs.coilbase.RepositoryContext;

/** Inserts and reads greetings with explicit SQL, joining transactions through the shared context. */
public final class GreetingRepository {
    private final RepositoryContext db;

    public GreetingRepository(RepositoryContext db) { this.db = db; }

    public void insert(Greeting greeting) {
        db.update("INSERT INTO greetings(id, message) VALUES (:id, :message)", update -> update
                .bind("id", greeting.id()).bind("message", greeting.message()).execute());
    }

    public List<Greeting> findAll() {
        return db.query("SELECT id, message FROM greetings ORDER BY id", query -> query
                .map((rs, ctx) -> new Greeting(rs.getString("id"), rs.getString("message"))).list());
    }
}
