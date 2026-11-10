# Changelog

## Unreleased

### Added

- `CoilBaseJexApplication.enableStaticResources()` serves classpath `/static/` at the HTTP root.
  Static serving is disabled by default. See [static resources](docs/comparison-with-spring-boot.md#static-resources).

## [0.1.0-preview3](https://github.com/PVRLabs/coilbase/releases/tag/0.1.0-preview3) — 2026-11-06

### Breaking changes

- `RepositoryContext` is now an interface; replace constructor calls with
  `RepositoryContext.create(...)` or construct `JdbiRepositoryContext` directly.

### Added and changed

- Added `TestRepositoryContext` for database-free, handle-free transactional
  callbacks in service unit tests. Real database tests remain necessary for SQL,
  rollback and isolation behavior.
- Split Hello tests into repository-only unit tests, H2 persistence tests and HTTP
  integration tests; updated examples and consumer fixtures for preview3.

## [0.1.0-preview2](https://github.com/PVRLabs/coilbase/releases/tag/0.1.0-preview2) — 2026-11-06

### Added

- `RepositoryContext` constructors accepting a `DataSource`, with optional
  `Dialects` or string dialect selection. Applications can now create a context
  with `new RepositoryContext(dataSource)` without constructing Jdbi explicitly.
  Existing Jdbi constructors remain available.

### Changed

- `CoilBaseJexApplication` uses the new DataSource constructors internally.
- Datasource configuration examples and consumer fixtures use the DataSource-first
  API and target preview2.
- Added a repeatable artifact release procedure in [docs/releasing.md](docs/releasing.md).

### Migration from preview1

- Removed automatic reflective `dispose()` calls for H2 `JdbcConnectionPool`.
  `CoilBaseJexApplication` still closes primary datasources implementing
  `AutoCloseable` on shutdown or startup failure. Applications using a datasource
  that does not implement `AutoCloseable` must arrange its cleanup explicitly.
  See [database configuration](docs/database-configuration.md) for examples.

## [0.1.0-preview1](https://github.com/PVRLabs/coilbase/releases/tag/0.1.0-preview1) — 2026-11-05

- Initial preview of `coilbase-core`: Jdbi-based repository contexts, transactions,
  optional dialect-specific SQL selection, and monitoring helpers; requires Java 17.
- Initial preview of `coilbase-jex`: application lifecycle, JSON, error handling,
  and optional StatLite endpoint integration; requires Java 21.
