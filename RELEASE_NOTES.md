# 0.1.0-preview3

This preview introduces a breaking repository context API and lightweight support
for database-free service tests.

- `RepositoryContext` is now an interface. Replace `new RepositoryContext(...)`
  with `RepositoryContext.create(...)`, accepting a `DataSource` or `Jdbi` and an
  optional enum or string dialect. `JdbiRepositoryContext` provides the production
  implementation and retains managed transaction and rollback-only semantics.
- `TestRepositoryContext` executes handle-free transactional callbacks directly.
  SQL, handles, and rollback-only operations are unsupported; use real database
  integration tests for transaction behavior. Testing support currently ships in
  Core and is expected to move to a separate module before the first stable release.
- Hello demonstrates repository-only Mockito unit tests alongside H2 persistence
  and HTTP integration tests.

See [the release procedure](docs/releasing.md) for artifact publication steps.

## Preview scope

CoilBase is an experimental Java foundation for explicit repositories and
transactions, with optional Avaje Jex integration. This is a preview; APIs and
behavior may change in later releases.

- `coilbase-core` requires Java 17 and supplies Jdbi-based repository,
  transaction, SQL selection, and opt-in monitoring helpers.
- `coilbase-jex` requires Java 21 and provides application lifecycle, JSON,
  error handling, and opt-in StatLite endpoint integration.
- Applications provide their own datasource and JDBC driver. Transactions are
  synchronous and scoped to one datasource; distributed transactions and
  automatic migrations are not supported.
- Jex monitoring counts unmatched routes with zero duration because Jex 3.6
  bypasses request filters for them. See [StatLite monitoring](docs/statlite-monitoring.md) for this
  and other limitations.

Artifacts are distributed through GitHub Releases and the PVR Labs Maven
repository at `https://pvrlabs.github.io/maven/`.
