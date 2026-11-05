# Framework Integrations

**Write once, run across frameworks** is an architectural goal: keep application
logic reusable while adapting the code that connects it to a host. It is not a
promise of zero changes or binary compatibility across frameworks.

CoilBase Core complements HTTP frameworks. It provides repository and transaction
building blocks over Jdbi without depending on an HTTP framework or DI container;
the host still handles routes, startup and framework-specific behavior.

## What stays the same

Repositories, services and business logic can remain independent of the HTTP host,
including their SQL statements, dialect selection and Core-managed transaction
boundaries. This assumes the datasource, database dialect and synchronous
transaction requirements remain compatible with the application.

## What changes

Applications adapt HTTP routes and controller adapters, framework dependencies
and bootstrap. Datasource creation and lifecycle, JSON configuration and error
handling may also need framework-specific wiring. Transactions are scoped to one
Jdbi instance and thread; changing hosts does not provide cross-thread or
distributed transaction propagation.

## Integration approaches explored

| Host | Approach |
| --- | --- |
| Avaje Jex | The optional `coilbase-jex` module supplies a lifecycle base with datasource and error-handling hooks; applications explicitly register routes or generated controller adapters. This is the recommended starting point. |
| Javalin | Use Core directly: create a `RepositoryContext`, wire repositories and services, and connect them to Javalin routes. The application owns bootstrap and resource lifecycle. |
| Micronaut | Reuse Core repositories and services while adapting controller registration, datasource wiring and lifecycle to Micronaut. The explored application lets Core own the relevant transaction boundaries; do not assume Micronaut-managed transactions automatically participate in them. |

Javalin and Micronaut approaches were explored in experiments, not packaged as
official CoilBase integration modules. The results apply to the tested applications
and configurations, not arbitrary framework versions or deployment environments.
For the packaged Jex path, follow [Getting Started](getting-started.md).

## Benchmarks and experiments

The framework experiments exercised shared application components across Jex,
Javalin and Micronaut HTTP stacks. See the [runtime comparisons](benchmarks.md)
for measurements, conditions and limitations.

**Pending:** Links to the public experiment article and GitHub source code will
be added when available.
