# 0.1.0-preview1 — release candidate

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

This candidate has not yet been published. Release artifacts are intended for
the PVR Labs Maven repository after final validation and approval.
