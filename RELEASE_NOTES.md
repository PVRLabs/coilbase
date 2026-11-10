# 0.1.0-preview4

This preview adds opt-in static resource serving to the Jex integration.

- Call `CoilBaseJexApplication.enableStaticResources()` to serve classpath
  `/static/` at the HTTP root. Static serving is disabled by default.
- The Hello example demonstrates static resources, and the native-image smoke
  workflow exercises the example on GraalVM.
- New documentation covers migration from Spring Boot and compares the two
  approaches.

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
