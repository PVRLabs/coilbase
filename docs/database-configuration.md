# Database configuration

Applications configure JDBC datasources and pass them to CoilBase explicitly.
Core requires no HTTP framework or configuration loader; the optional Jex base
provides a primary-datasource convention. Jex applications require Java 21+.

## Primary datasource with Jex

Override `createPrimaryDataSource()` to provide a configured `DataSource`.
During startup, CoilBase creates a Jdbi instance and a `RepositoryContext` for
that datasource.

The protected `db()` accessor is available in `registerRoutes(Jex)` and afterward.
Pass it into repositories and services through constructors. Do not call it from
the application constructor or datasource factory.

By default, no datasource is configured. HTTP-only applications can run without
one, and calling `db()` without a datasource throws `IllegalStateException`.
SQL dialect configuration is optional and is explained below.

## Supporting multiple databases with SQL dialects

CoilBase makes it easier to switch between database engines or support several
of them while retaining the same repositories and business logic. The datasource
chooses which database to connect to; the dialect selects which SQL variant to use.

Much SQL can be shared across databases and needs no special configuration. When
syntax differs, define a default statement and database-specific overrides:

```java
var recentGreetings = SqlStatement.of(
    "SELECT id, message FROM greetings ORDER BY id FETCH FIRST 10 ROWS ONLY")
    .postgres("SELECT id, message FROM greetings ORDER BY id LIMIT 10");
```

Configure the SQL dialect once for the repository context:

```java
var db = RepositoryContext.create(dataSource, Dialects.POSTGRES);
```

In a Jex application, optionally override `primaryDialect()` to return
`Dialects.POSTGRES`. The context selects SQL when a repository executes the
statement through its `query` or `update` methods:

| Configuration | SQL selected |
| --- | --- |
| No dialect configured | Default SQL |
| PostgreSQL configured | PostgreSQL override, if present; otherwise default SQL |
| Another dialect without an override | Default SQL |

Applications using only default SQL need no dialect configuration: use
`RepositoryContext.create(dataSource)` or leave the Jex `primaryDialect()` hook unchanged.

The datasource and JDBC driver remain application-controlled. CoilBase does not
automatically switch connections, detect database engines, translate SQL, or
guarantee SQL compatibility. Custom string dialect keys are also supported through
`RepositoryContext`; the Jex `primaryDialect()` hook uses the `Dialects` enum.
String keys are trimmed and case-insensitive, but otherwise exact: `postgres`
matches `POSTGRES`, while `PostgreSQL` is a distinct key.

## H2: no external database

[Hello CoilBase](../examples/hello-coilbase) uses HikariCP with an in-memory H2 database:

```java
@Override protected DataSource createPrimaryDataSource() {
    var pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1");
    pool.setUsername("sa");
    pool.setPassword("");
    pool.setMaximumPoolSize(4);
    pool.setMinimumIdle(1);
    return pool;
}
```

Import `com.zaxxer.hikari.HikariDataSource` and `javax.sql.DataSource`; add
HikariCP and H2 as application dependencies. The pool is created during startup.
`DB_CLOSE_DELAY=-1` retains the named in-memory database between connections
until JVM exit; a process restart loses its data. No Docker or external database
is required. See [Getting Started](getting-started.md) for build and run commands.

## PostgreSQL with HikariCP

For a production-oriented PostgreSQL application, supply a Hikari datasource.
These application dependencies include Core transitively through `coilbase-jex`.
Configure the public PVR Labs Maven repository as shown in
[Installation](../README.md#installation).

```xml
<dependencies>
  <dependency>
    <groupId>xyz.pvrlabs</groupId>
    <artifactId>coilbase-jex</artifactId>
    <version>0.1.0-preview2</version>
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

`RepositoryContext` itself never closes a datasource. The Jex base closes the
primary datasource returned by `createPrimaryDataSource()` once, on startup failure
or Jex shutdown, when it implements `AutoCloseable`. Otherwise, the application
is responsible for cleanup. Hello CoilBase returns an `AutoCloseable`
`HikariDataSource`, so it needs no separate cleanup hook.
If cleanup fails during startup failure handling, the original startup failure
is preserved and the cleanup failure is suppressed. A factory that allocates
resources and fails before returning them must clean those resources up itself.
Other resources and additional datasources remain application-owned.

## Additional datasources

Construct another context explicitly, using an application-configured
`DataSource reportingDataSource`:

```java
var reportingDb = new pvrlabs.coilbase.RepositoryContext(
    reportingDataSource,
    pvrlabs.coilbase.Dialects.POSTGRES);
```

Omit the dialect argument to use default SQL:
`RepositoryContext.create(reportingDataSource)`.

Pass `reportingDb` to reporting repositories and the primary `db()` to primary
repositories. Register shutdown cleanup and handle startup failures for the extra
pool yourself; the Jex base does not discover or close it.

For advanced configuration, pass a preconfigured Jdbi instance to a
`RepositoryContext` constructor.

Managed transactions are scoped to a thread and Jdbi instance. Separate contexts
constructed from a datasource have independent transactions, even when they share
the same datasource. Contexts passed the same Jdbi instance share its managed scope.
A primary transaction does not enclose work on `reportingDb`; a reporting commit
can survive a later primary rollback. There is no cross-datasource atomicity or
distributed transaction support. Repositories that must participate together
should share their context/Jdbi instance.
