package pvrlabs.coilbase.example.hello;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Post;
import io.avaje.http.api.Produces;
import java.util.List;

/** Maps greeting HTTP requests to the service; Avaje generates its routing adapter at build time. */
@Controller("/greetings")
public class GreetingController {
    private final GreetingService service;
    /** JSON request body containing the name to greet. */
    public record NewGreeting(String name) {}

    public GreetingController(GreetingService service) { this.service = service; }

    @Get
    public List<Greeting> findAll() { return service.findAll(); }

    @Post
    @Produces(statusCode = 201)
    public Greeting greet(NewGreeting input) {
        return service.greet(input == null ? null : input.name());
    }
}
