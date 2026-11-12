# Migrate a Spring Boot JDBC application to CoilBase and Jex

This guide shows how to migrate a Spring MVC and JDBC application to CoilBase with Avaje Jex while preserving its HTTP and database behavior. It uses CoilBase Preview 4. The examples are drawn from a quote-ingestion service and a portfolio application with holdings, valuation, and transactional edits.

Start with the [CoilBase installation instructions](../README.md#installation) and the [Spring Boot comparison](comparison-with-spring-boot.md).

Keep the existing application running as a baseline. Capture its routes, response shapes, validation rules, configuration, database changes, and rollback behavior before replacing framework components. Run the same behavior checks against separate databases for the original and migrated applications.

For a worked JPA-to-explicit-SQL example, see the
[CoilBase PetClinic migration guide](https://github.com/ejboy/coilbase-petclinic/blob/main/docs/migration.md).
It illustrates one application design, not a required CoilBase architecture.
Migrating JPA relationships and transaction behavior requires explicit decisions
about loading, writes and rollback. Readiness checks should exercise the resources
the application needs; an HTTP root response alone may not establish database readiness.

## Choose what to carry over

| Spring application | CoilBase and Jex | Migration |
| --- | --- | --- |
| Domain records, calculations, validation and SQL | Ordinary Java records, calculations, validation and SQL | Carry over where dependencies permit; verify results and errors |
| Constructor-injected components | Constructors called explicitly at startup | Adapt wiring; keep controller, service and repository responsibilities |
| Spring MVC controllers | Avaje HTTP controllers or Jex routes | Adapt registration and HTTP binding |
| `JdbcClient` and row mappers | `RepositoryContext`, Jdbi and `SqlStatement` | Adapt bindings and row mapping; preserve SQL semantics |
| `@Transactional` | Explicit `db.transactional` callback | Place the boundary around the complete business operation |
| Spring configuration and lifecycle | Application configuration and explicit startup/shutdown | Preserve the settings and lifecycle behavior your deployment uses |
| Spring exception advice and `ProblemDetail` | Jex exception handlers and response writing | Map status, media type, body and messages explicitly |

Spring Data repositories, JPA/Hibernate entity management, security, Actuator, scheduling annotations, and Spring's complete property and transaction propagation model do not have automatic drop-in migrations in the examples below. Inventory each feature in your application and choose an appropriate replacement or redesign; this guide does not establish equivalence for them.

## Wire controllers, services and repositories

Spring can discover components and inject their constructors. With CoilBase, construct the same layers in your application setup. For example, the Portfolio application creates its repositories, service and route handlers in `registerRoutes`:

```java
@Override
protected void registerRoutes(Jex jex) {
    db().executeScriptResource("/schema.sql");
    var quotes = new QuoteRepository(db());
    var portfolios = new PortfolioRepository(db());
    new PortfolioInitializer(portfolios, db(), config.seedUsers()).initialize();
    var service = new PortfolioService(db(), portfolios);
    new PortfolioController(service).register(jex);
    new ReadinessController(new ReadinessService(portfolios, quotes)).register(jex);
}
```

This excerpt leaves out quote routes and scheduler setup. Controllers remain responsible for HTTP, services for domain operations, and repositories for SQL. Avaje HTTP generated route adapters are another option: register the generated adapter with `jex.routing(...)` as in the quote application.

## Migrate JDBC repositories

Keep SQL and its observable semantics, then adapt parameter binding and result mapping. A CoilBase repository can use a shared `RepositoryContext` and an immutable statement:

```java
class PortfolioRepository extends Repository {
    private static final SqlStatement LOCK =
            SqlStatement.of("SELECT id FROM users WHERE id=:id FOR UPDATE");

    PortfolioRepository(RepositoryContext db) {
        super(db);
    }

    boolean lockUser(long id) {
        return db.query(LOCK,
                q -> q.bind("id", id).mapTo(Long.class).findOne().isPresent());
    }
}
```

Use dialect-specific statement overrides only where needed. Verify SQL behavior on each database you support, including locking, timestamp handling and constraints.

## Replace declarative transactions

A Spring service can declare its boundary:

```java
@Transactional
public void setQuantity(long userId, String symbol, BigDecimal quantity) {
    validateHolding(symbol, quantity);
    if (!repository.lockUser(userId)) throw new UserNotFoundException();
    if (quantity.signum() == 0) repository.deleteHolding(userId, symbol);
    else repository.saveHolding(userId, symbol, quantity);
}
```

In the migrated service, validate before starting the transaction and explicitly group the database operations:

```java
public void setQuantity(long userId, String symbol, BigDecimal quantity) {
    validateHolding(symbol, quantity);
    db.transactional(() -> edit(userId, symbol, quantity));
}

private void edit(long userId, String symbol, BigDecimal quantity) {
    if (!repository.lockUser(userId)) throw new UserNotFoundException();
    if (quantity.signum() == 0) repository.deleteHolding(userId, symbol);
    else repository.saveHolding(userId, symbol, quantity);
}
```

The lock must remain held until the transaction commits, including when an outer operation calls this service. Repository methods using the same `RepositoryContext` on the same thread participate in the caller's transaction; callers that perform batch writes directly must establish their own boundary. Fetch external data and validate it before opening a short database transaction when the business operation permits it. Test a failure after an earlier write, verify no partial state remains, and verify a subsequent operation succeeds.

Nested operations join the existing transaction when they use the same `RepositoryContext` on the same thread. Separate contexts created from a `DataSource` have independent transactions, even when they share that datasource. Transaction participation is scoped to the underlying Jdbi instance and thread; if you explicitly share a Jdbi instance between contexts, that distinction matters. Do not assume every Spring propagation mode, isolation setting, read-only hint or rollback rule carries over automatically; review each transaction against its business contract.

## Exception handling and Problem Details

Spring MVC can map a domain exception to a `ProblemDetail` response and select `application/problem+json`:

```java
@ExceptionHandler(UserNotFoundException.class)
ProblemDetail userNotFound(UserNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
}
```

With Jex, register the handler and write the response explicitly. Keep the helper in the application:

```java
@Override
protected void configureErrorHandling(Jex jex) {
    super.configureErrorHandling(jex);
    jex.error(UserNotFoundException.class,
            (ctx, failure) -> problem(ctx, 404, "Not Found", failure.getMessage()));
}

private void problem(Context ctx, int status, String title, String detail) {
    final String body;
    try {
        body = objectMapper().writeValueAsString(Map.of(
                "title", title, "status", status,
                "detail", detail, "instance", ctx.path()));
    } catch (JsonProcessingException failure) {
        throw new IllegalStateException("Unable to serialize problem response", failure);
    }
    ctx.status(status).contentType("application/problem+json").write(body);
}
```

In Jex 3.6, `ctx.json()` selects `application/json` and writes the response. Setting the content type afterward cannot change committed headers; setting it before `json()` is overridden. Set the desired media type and write the serialized body instead. Test status, media type and the fields your clients rely on. Framework-generated malformed-request responses may differ, so test those separately.

## Preserve configuration and lifecycle

Replace Spring property binding with application-owned configuration appropriate to your deployment. Preserve environment names, defaults and validation rules that operators already use. For example, Portfolio retains `SERVER_PORT`, falls back to CoilBase's `PORT` convention and then uses 8080:

```java
String port = System.getenv("SERVER_PORT");
if (port == null) port = System.getenv("PORT");
app.run(port == null || port.isBlank() ? 8080 : Integer.parseInt(port));
```

This example reads environment variables; it does not reproduce Spring's complete property-source precedence. Check database URL options, credentials, pool settings and timezone explicitly. Start background work only when the application is ready to run, stop it on shutdown, and close the server if post-start initialization fails. Decide how initialization and readiness report database failures.

## Enable static resources when needed

Spring Boot serves conventional static resources automatically. In CoilBase/Jex, opt in when the application needs them:

```java
new HelloApplication().enableStaticResources().start();
```

The default uses classpath `static/` and `index.html`. Verify asset paths, content types, missing resources and API coexistence. The Portfolio application has no dashboard and does not enable static serving.

## Verify the migration

`TestRepositoryContext` supports isolated service tests; H2 integration tests exercise actual SQL and rollback. Use independent data for each application and compare behavior rather than implementation details:

1. Check routes, success and error status codes, response fields and media types.
2. Compare committed database state after inserts, edits and deletes.
3. Force a failure after an earlier database write; check rollback and recovery.
4. Exercise concurrent edits and verify locks are held until commit where required.
5. Check initialization, readiness, shutdown and restart with persisted state.
6. Repeat database-specific checks for every supported database and test the packaged application using its real deployment configuration.

The example comparison covered quote ingestion and Portfolio scenarios using H2, including business errors, decimal valuation, rollback, lock lifetime and restart. It does not establish exhaustive HTTP equivalence, Actuator equivalence, or PostgreSQL/MariaDB behavior. Validate those areas against your own application's requirements.
