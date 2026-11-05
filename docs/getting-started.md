# Getting started

Build a greetings API with an annotated controller, database-backed repository,
service transaction and explicit constructor wiring. Annotated Avaje HTTP
controllers are the recommended starting point for CoilBase applications using Jex;
native Jex routes remain available. H2 runs in memory, using its built-in
`JdbcConnectionPool`; there is no Docker, external database, HikariCP or DI container.
The [complete example](../examples/hello-coilbase) uses Core for persistence and
the optional Jex module for HTTP and JSON. This HTTP example requires JDK 21+
and Maven; Core alone supports Java 17+.

## 1. Build and run the example

From the root of your CoilBase checkout:

```sh
mvn -f examples/hello-coilbase/pom.xml clean verify
java -jar examples/hello-coilbase/target/hello-coilbase-0.1.0-preview1.jar
```

The example POM configures `https://pvrlabs.github.io/maven/` and resolves
`xyz.pvrlabs:coilbase-core:0.1.0-preview1` and
`xyz.pvrlabs:coilbase-jex:0.1.0-preview1` from the public repository. It does
not require a locally installed CoilBase build. For your own project, see the
[repository configuration and dependencies](../README.md#installation).

The example POM configures `avaje-http-jex-generator` on Maven's annotation
processor path, with processing explicitly enabled for JDK 21 and newer. It generates
`GreetingController$Route` under `target/generated-sources/annotations`, then compiles
it with the application. No DI container or runtime controller discovery is needed.
The generator is a build dependency, not a shipped runtime dependency.

The build copies runtime dependencies into `target/lib/`, referenced by the JAR
manifest. Keep that directory beside the JAR. The server starts on port 8080;
set `PORT=8081` before the Java command to change it.

## 2. Call the API

In another terminal:

```sh
curl -i http://localhost:8080/greetings
# 200, []

curl -i -X POST http://localhost:8080/greetings \
  -H 'Content-Type: application/json' -d '{"name":"Ada"}'
# 201, {"id":"<generated UUID>","message":"Hello, Ada!"}

curl -i http://localhost:8080/greetings
# 200, [{"id":"<same UUID>","message":"Hello, Ada!"}]

curl -i -X POST http://localhost:8080/greetings \
  -H 'Content-Type: application/json' -d '{"name":""}'
# 400, {"error":"bad_request"}
```

Blank, missing or over-80-character names return 400. Malformed JSON also returns
400; unexpected failures use the Jex helper's sanitized 500 response. Press Ctrl-C
to stop. Data is intentionally lost when the process exits; restarting starts empty.

## 3. Follow the wiring

[HelloApplication](../examples/hello-coilbase/src/main/java/pvrlabs/coilbase/example/hello/HelloApplication.java)
provides the pool:

```java
@Override protected DataSource createPrimaryDataSource() {
    return JdbcConnectionPool.create("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1", "sa", "");
}
```

The base creates a `RepositoryContext` that selects default SQL. Override `primaryDialect()`
when statements need dialect-specific SQL; see
[Supporting multiple databases with SQL dialects](database-configuration.md#supporting-multiple-databases-with-sql-dialects).
The application explicitly calls
`db().executeScriptResource("/schema.sql")` to read
[`src/main/resources/schema.sql`](../examples/hello-coilbase/src/main/resources/schema.sql)
as UTF-8 and execute it through Jdbi before registering the controller adapter.
`CREATE TABLE IF NOT EXISTS` makes this repeatable without deleting existing data;
missing resources or SQL errors fail startup and dispose the pool. The dependency
graph is ordinary Java:

```java
db().executeScriptResource("/schema.sql");
var repository = new GreetingRepository(db());
var service = new GreetingService(db(), repository);
var controller = new GreetingController(service);
jex.routing(new GreetingController$Route(controller));
```

[GreetingController](../examples/hello-coilbase/src/main/java/pvrlabs/coilbase/example/hello/GreetingController.java)
uses Avaje HTTP annotations, with ordinary method arguments and return values:

```java
@Controller("/greetings")
public final class GreetingController {
    private final GreetingService service;
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
```

The generated adapter binds the JSON request, invokes the controller and serializes
its result. `@Produces` preserves the POST's 201 response; GET returns 200.

The base installs safe JSON error handling before calling the application's
configuration hook. Hello CoilBase only needs to map its own validation exception:

```java
@Override protected void configureErrorHandling() {
    mapError(GreetingService.InvalidNameException.class, 400);
}
```

Invalid names return HTTP 400 with `{"error":"bad_request"}`. Malformed or
mismatched JSON already receives the same response without application mappings.
Unmapped unexpected exceptions return 500 with `{"error":"internal_error"}`;
exception messages are not included in these default bodies.

For custom codes and mapping precedence, see the
[`mapError` Javadoc](../coilbase-jex/src/main/java/pvrlabs/coilbase/jex/CoilBaseJexApplication.java).
The `configureErrorHandling(Jex)` hook allows native customization, and
`JexErrors.install()` is available for standalone Jex applications.

[GreetingRepository](../examples/hello-coilbase/src/main/java/pvrlabs/coilbase/example/hello/GreetingRepository.java)
uses `db.update` for bound inserts and `db.query` for mapped reads. Callbacks
receive native Jdbi statements and consume results while their handles are open.
There is no generated repository, SQL rewriting or automatic schema discovery.
For script execution, transactions and migrations, see
[Database Configuration](database-configuration.md#explicit-sql-scripts).

[GreetingService](../examples/hello-coilbase/src/main/java/pvrlabs/coilbase/example/hello/GreetingService.java)
validates a name, then inserts a greeting inside `db.transactional`. The repository
uses that same managed transaction because it shares the context's Jdbi instance
and thread. `transactional` supports both value-returning and void work; use
`inTransaction` when the callback needs a native Jdbi handle.
Escaping exceptions roll back; caught failures need explicit `markRollbackOnly()`
when commit must be prevented. Transactions do not propagate to another thread.
The example tests also verify rollback when an enclosing service operation fails.

The base owns the returned pool and disposes it on shutdown or startup failure;
see [datasource ownership](database-configuration.md#ownership-and-shutdown).
`DB_CLOSE_DELAY=-1` keeps the in-memory database alive between connections until JVM exit.

## 4. Adapt it

The [example POM](../examples/hello-coilbase/pom.xml) declares
`xyz.pvrlabs:coilbase-core`, `xyz.pvrlabs:coilbase-jex`, H2 and Avaje HTTP's
annotation API. Core remains independent of HTTP and annotation processing. Generated adapters'
Jakarta Inject annotations are compile-time metadata (`provided` scope), not a DI
runtime; the optional Avaje HTTP Inject plugin is excluded. Jex supplies HTTP,
Jackson and Java time support; it is optional for other applications. Keep Core,
repositories and services when choosing another HTTP host, and write that host's
routes and startup wiring yourself.

For Jex, annotated controllers are the recommended application structure. Native
routes can coexist with them, for example `jex.get("/ping", ctx -> ctx.text("pong"))`
inside `registerRoutes`. Both use the same Jex server and explicit lifecycle.

For PostgreSQL with HikariCP, additional datasources and lifecycle details, see
[Database Configuration](database-configuration.md).

The example is a separate Maven project, outside the published library reactor
and JARs. It is licensed under [Apache-2.0](../LICENSE). Database migrations,
authentication and production deployment are beyond this introduction;
this introduction keeps the database embedded and configuration minimal.
