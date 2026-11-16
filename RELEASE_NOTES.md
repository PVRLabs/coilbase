# 0.1.0-preview5

This preview adds automatic primary database health to opt-in Jex monitoring.

- `enableMonitoring()` reports cached primary database health as `UP` or `DOWN`
  when a datasource is configured, alongside HTTP and JVM metrics. Health is
  omitted until the first check completes and when no datasource is configured.
- An optional slow test verifies collection by a running StatLite instance.
- The Hello example includes a runnable distribution, with updated deployment
  and monitoring documentation.

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
