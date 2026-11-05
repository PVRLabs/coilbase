# Database configuration

Applications configure JDBC datasources and pass them to CoilBase explicitly.
Core requires no HTTP framework or configuration loader; the optional Jex base
provides a primary-datasource convention. Jex applications require Java 21+.

## Primary datasource with Jex

Override `createPrimaryDataSource()` to return a configured `DataSource`. By default,
statements use their default SQL. Optionally override `primaryDialect()` to select
dialect-specific SQL, using a key such as `Dialects.H2` or `Dialects.POSTGRES`.
When no override matches, the default SQL is used. String dialect keys are trimmed
and case-insensitive, but are otherwise exact: `postgres` matches `POSTGRES`, while `PostgreSQL` is a distinct
key, not an alias. Core's `RepositoryContext` API supports custom string keys;
the Jex `primaryDialect()` hook accepts only `Dialects` enum values. The dialect
is explicit; it does not select a driver, detect
the database or rewrite SQL.

During `start()`, `CoilBaseJexApplication` creates a Jdbi instance and a
`RepositoryContext` for that datasource. Its protected `db()` accessor is available
in `registerRoutes(Jex)` and afterward: pass it into repositories and services
through constructors. Do not call it from the constructor or datasource factory.
By default the datasource is `null`; HTTP-only applications need no dialect,
and calling `db()` without a datasource throws `IllegalStateException`.

## SQL statements and dialect overrides

No dialect is required: `new RepositoryContext(jdbi)` selects default SQL, and
Jex applications need not override `primaryDialect()`. Define a statement with
shared SQL and add an override only when database-specific syntax is needed:

```java
var recentGreetings = SqlStatement.of(
    "SELECT id, message FROM greetings ORDER BY id FETCH FIRST 10 ROWS ONLY")
    .postgres("SELECT id, message FROM greetings ORDER BY id LIMIT 10");
```

| Configuration | SQL selected |
| --- | --- |
| No dialect configured | Default SQL |
| Dialect with a matching override | Override SQL |
| Dialect without a matching override | Default SQL |

Most SQL can remain shared across databases. Dialect selection is explicit:
CoilBase does not detect the database or validate SQL compatibility. Core's
`RepositoryContext` also accepts custom string dialect keys; the Jex
`primaryDialect()` hook uses the `Dialects` enum.

## H2: no external database

[Hello CoilBase](../examples/hello-coilbase) uses H2's built-in pool:

```java
@Override protected DataSource createPrimaryDataSource() {
    return JdbcConnectionPool.create("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1", "sa", "");
}
```

These members belong in your `CoilBaseJexApplication` subclass. Import
`org.h2.jdbcx.JdbcConnectionPool` and `javax.sql.DataSource`; add
`com.h2database:h2:2.5.250` as an application dependency. The pool is created during startup, with no constructor plumbing or
application-level pool field.
`DB_CLOSE_DELAY=-1` retains the named in-memory database between connections
until JVM exit; a process restart loses its data. No Docker or external database
is required. See [Getting Started](getting-started.md) for build and run commands.

## PostgreSQL with HikariCP

For a production-oriented PostgreSQL application, supply a Hikari datasource.
These application dependencies include Core transitively through `coilbase-jex`;
install CoilBase locally first, as described in [Getting Started](getting-started.md).

```xml
<dependencies>
  <dependency>
    <groupId>xyz.pvrlabs</groupId>
    <artifactId>coilbase-jex</artifactId>
    <version>0.1.0-preview1</version>
  </dependency>
  <dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>7.0.2</version>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.13</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

Provision a database and user separately. Set environment variables in the process
that launches your application; for example, supply `DB_URL` as
`jdbc:postgresql://localhost:5432/coilbase?tcpKeepAlive=true`, plus `DB_USER` and
`DB_PASSWORD`. The application below reads them explicitly; CoilBase does not
load database configuration from the environment itself. `PORT` is handled by
the Jex base's `start()` method (default 8080).

Save this as `PostgresApplication.java` in your application's source directory:

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.avaje.jex.Jex;
import javax.sql.DataSource;
import pvrlabs.coilbase.Dialects;
import pvrlabs.coilbase.jex.CoilBaseJexApplication;

public final class PostgresApplication extends CoilBaseJexApplication {
    public static void main(String[] args) { new PostgresApplication().start(); }

    @Override protected DataSource createPrimaryDataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl(requiredEnv("DB_URL"));
        config.setUsername(requiredEnv("DB_USER"));
        config.setPassword(requiredEnv("DB_PASSWORD"));
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    @Override protected Dialects primaryDialect() { return Dialects.POSTGRES; }

    @Override protected void registerRoutes(Jex jex) {
        jex.get("/database", ctx -> {
            int value = db().query("SELECT 1", query -> query.mapTo(Integer.class).one());
            ctx.json(value);
        });
    }

    private static String requiredEnv(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name);
        return value;
    }
}
```

This small native route demonstrates database access; wire annotated controllers
as in Hello CoilBase for application endpoints. Four connections are a starting
pool bound, not a universal production setting. See
[Hikari configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby)
and [pgJDBC connection options](https://jdbc.postgresql.org/documentation/use/)
for deployment-specific pool sizing, TLS and timeouts. No explicit driver-class
loading is needed with the driver on the runtime classpath.

## Explicit SQL scripts

Given a configured `RepositoryContext db`:

```java
db.executeScript("CREATE TABLE IF NOT EXISTS app_events(id BIGINT PRIMARY KEY)");
db.executeScriptResource("/schema.sql");
```

Place `schema.sql` in `src/main/resources`. Resource paths are relative to the
classpath root, with an optional leading slash; loading uses UTF-8 and the thread
context class loader, falling back to CoilBase's loader if none is set. A missing
resource throws `IllegalArgumentException`; read failures throw
`UncheckedIOException`, and Jdbi SQL failures propagate.

Nothing discovers or executes scripts automatically. In Hello CoilBase,
`registerRoutes` explicitly calls `db().executeScriptResource("/schema.sql")`
before registering the controller adapter, so initialization failure aborts startup.
Both methods use Jdbi scripts and the context's managed handle. They do **not**
start a transaction; they join an existing transaction, or use the datasource's
JDBC autocommit policy. To request a transaction explicitly:

```java
db.transactional(() -> db.executeScriptResource("/seed.sql"));
```

Supply `seed.sql` yourself; transaction behavior for DDL remains database-specific.
These methods provide neither migration versions nor history. Production
applications can use Flyway or Liquibase for versioned migrations.

## Ownership and shutdown

`RepositoryContext` itself never closes a datasource. The Jex base owns only the
primary datasource returned by `createPrimaryDataSource()`. It attempts cleanup
once, on startup failure or Jex shutdown:

- `AutoCloseable` datasources, including `HikariDataSource`: call `close()`.
- H2's exact `org.h2.jdbcx.JdbcConnectionPool` class: invoke its public `dispose()`
  reflectively. H2 is not `AutoCloseable`; this targeted support adds no H2
  dependency to the Jex library and performs no generic lifecycle discovery.

Hello CoilBase therefore needs no disposal hook or startup wrapper: its factory
returns the H2 pool directly, and `main` calls `new HelloApplication().start()`.
If cleanup fails during startup failure handling, the original startup failure
is preserved and the cleanup failure is suppressed. A factory that allocates
resources and fails before returning them must clean those resources up itself.
Other resources and additional datasources remain application-owned.

## Additional datasources

Construct another context explicitly, using an application-configured
`DataSource reportingDataSource`:

```java
var reportingDb = new pvrlabs.coilbase.RepositoryContext(
    org.jdbi.v3.core.Jdbi.create(reportingDataSource),
    pvrlabs.coilbase.Dialects.POSTGRES);
```

Omit the dialect argument to use default SQL:
`new RepositoryContext(Jdbi.create(reportingDataSource))`.

Pass `reportingDb` to reporting repositories and the primary `db()` to primary
repositories. Register shutdown cleanup and handle startup failures for the extra
pool yourself; the Jex base does not discover or close it.

Managed transactions are scoped to a thread and Jdbi instance. Separate Jdbi
instances have independent transactions, even if they use the same datasource.
A primary transaction does not enclose work on `reportingDb`; a reporting commit
can survive a later primary rollback. There is no cross-datasource atomicity or
distributed transaction support. Repositories that must participate together
should share their context/Jdbi instance.
