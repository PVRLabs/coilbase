# Hello CoilBase

A small greetings API using annotated Avaje HTTP controllers, CoilBase Core,
optional Jex integration and H2's built-in `JdbcConnectionPool`. This is the
recommended Hello CoilBase layout: controller → service → repository, wired
through constructors. Requires JDK 21+ and Maven; no external database is needed.

## Build and run

From the CoilBase repository root (the library artifacts are not published yet):

```sh
mvn clean install
mvn -f examples/hello-coilbase/pom.xml clean verify
java -jar examples/hello-coilbase/target/hello-coilbase-0.1.0-preview1.jar
```

Maven runs the Avaje Jex annotation processor, compiles the generated controller
adapter, runs the tests and packages the application. Keep `target/lib/` beside
the JAR. The default port is 8080; prefix the Java command with `PORT=8081` to
choose another port.

## Try the API

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

The factory creates and returns the H2 pool directly. The Jex base disposes it on
shutdown or startup failure, so `main` only calls `new HelloApplication().start()`.
Press Ctrl-C to stop. H2 data lives only for the process lifetime; a restart starts
empty.

## Layout

- `src/main/java/`: application startup, annotated controller, service, repository and greeting record.
- [`src/main/resources/schema.sql`](src/main/resources/schema.sql): repeatable table initialization.
- `src/test/java/`: HTTP, transaction, schema-repeatability and pool-cleanup tests.

[GreetingController](src/main/java/pvrlabs/coilbase/example/hello/GreetingController.java)
uses Avaje's `@Controller`, `@Get` and `@Post`; `@Produces(statusCode = 201)`
sets the creation response status. Startup wires each object explicitly:

```java
var repository = new GreetingRepository(db());
var service = new GreetingService(db(), repository);
var controller = new GreetingController(service);
jex.routing(new GreetingController$Route(controller));
```

`GreetingController$Route` is generated in `target/generated-sources/annotations`.
The POM explicitly configures `avaje-http-jex-generator` as an annotation processor
and excludes the optional HTTP Inject plugin. No DI container, scanning or runtime
route reflection is used. Native Jex routes remain available alongside controllers.

The base installs safe default error handling automatically. The example's
no-argument `configureErrorHandling()` hook maps `GreetingService.InvalidName` to
400/`bad_request`. Malformed/mismatched JSON already uses that response by default;
unmapped failures return 500/`internal_error`.

Before registration, startup calls `db().executeScriptResource("/schema.sql")`
to read UTF-8 SQL from the classpath and execute it through Jdbi. Missing/unreadable
resources or SQL failures abort startup and dispose the pool. `CREATE TABLE IF NOT EXISTS`
preserves existing data on repeated initialization; there is no automatic discovery
or migration.

For a walkthrough of constructor wiring, transactions and pool ownership, see
[Getting Started](../../docs/getting-started.md). This standalone example stays
outside the library reactor and published library JARs.

Licensed under [Apache-2.0](../../LICENSE).
