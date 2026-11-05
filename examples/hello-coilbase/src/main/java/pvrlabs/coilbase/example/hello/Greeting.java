package pvrlabs.coilbase.example.hello;

/** A stored greeting, returned as JSON by the HTTP API. */
public record Greeting(String id, String message) {}
