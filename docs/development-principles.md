# Development Principles

These principles guide contributors evolving CoilBase. They capture the constraints
behind its design; current libraries, versions and experiment results are choices
or evidence, not permanent requirements. See the [README](../README.md) for the
current API and supported behavior.

## Keep behavior explicit and understandable

Give applications familiar repository, service and controller organization using
ordinary Java, constructor wiring and visible transaction boundaries. A feature
should remove meaningful repeated plumbing while leaving the underlying APIs
available. Prefer explicit registration and configuration to implicit discovery,
proxies or hidden conventions. Avoid adding abstractions or configuration options
until a concrete use case justifies their cost. Generated code can be useful when
its registration and behavior remain explicit.

CoilBase has a deliberately narrow scope: it is not a DI container, ORM, migration
system or universal HTTP abstraction. Evaluate additions against that scope and
against using the underlying library directly. Familiarity with Spring is useful;
reproducing its machinery or every behavior is not the goal.

## Preserve a lightweight, independent Core

Core must remain usable without an HTTP framework or DI container. Keep hosting,
monitoring and other integrations optional and focused; do not impose their
dependencies or background activity on applications that do not use them. Where
an integration needs host-specific behavior, expose the host's native APIs rather
than inventing a generic layer in anticipation of future hosts. Small optional
helpers in Core should remain independent of any particular monitoring service.
Keep optional functionality behind explicit opt-in even when it lives in an
existing module: avoid loading or initializing its implementation and starting
its background work when it is disabled. Initialize enabled functionality at a
predictable lifecycle point so configuration and startup failures remain visible.
This applies to features such as monitoring or static-resource support; module
packaging alone does not make a feature mandatory.

Treat memory-constrained deployments and small VPS environments as first-class
use cases. Assess dependencies and features for memory, startup cost, runtime
threads and background work, including the effects on the complete application.
Measure meaningful tradeoffs under representative conditions before optimizing;
avoid speculative tuning and arbitrary resource limits. An experiment's RSS or
startup time describes its workload and environment, not a framework guarantee.

## Design for Native Image and HotSpot

Consider GraalVM Native Image compatibility throughout design and dependency
selection, alongside a good HotSpot experience. Prefer statically analyzable code;
avoid unnecessary reflection, dynamic class loading and classpath scanning. When
dynamic access is needed, provide or arrange appropriate reachability metadata and
verify the relevant paths. Consider dependencies' native compatibility and
footprint, not just whether they compile on the JVM.

Maintain regular JVM tests and the Native Image smoke check; extend native coverage
when a change introduces new dynamic access or materially different behavior. The
existing example smoke test exercises selected paths, not every application or
dependency configuration. CI startup, size and RSS measurements are diagnostic
references, not strict benchmarks or performance gates.

## Keep persistence and lifecycle predictable

Keep persistence SQL-first, with explicit transaction boundaries and access to
the underlying database APIs. Preserve the documented transaction contract,
including nested participation and rollback behavior; do not silently introduce
new propagation or migration semantics. A dialect selection is not proof that a
database has been tested. Test behavior against the relevant database when making
compatibility claims.

Follow the patterns in `examples/hello-coilbase` as the reference for idiomatic
CoilBase application development. When supporting multiple SQL dialects, use
`SqlStatement` with dialect-specific overrides rather than scattering
database-specific SQL selection throughout the application. See
[Database Configuration](database-configuration.md) for guidance.

Make resource ownership and lifecycle effects clear: who creates and closes a
datasource, when initialization occurs, and what happens on startup failure and
shutdown. Preserve the original failure when cleanup also fails. Avoid hidden
resource creation, transactions or other side effects, and ensure resources do
not escape the scope that owns them.

## Keep testing focused and fast

Protect meaningful behavior and realistic failures with a small number of
representative tests at the appropriate level. Avoid duplicating coverage across
unit, integration and end-to-end tests unless the distinction catches a concrete risk.

Keep routine Maven tests fast, including integration tests. Prefer lightweight local
dependencies such as H2, deterministic synchronization and controlled failure
injection. Avoid unnecessary sleeps, external services and heavyweight infrastructure
in the default suite. Evaluate tests or groups taking roughly 30 seconds or more for
an optional suite, considering their value, runtime variability and cumulative cost;
prefer simplifying a slow test before excluding it.

Run heavyweight tests only when explicitly requested locally, and include them in CI
using a documented Maven profile or property. Keep `mvn test` and `mvn verify`
practical for frequent development. Keep long-running workloads, external-system
demonstrations and performance experiments separate from routine verification.
Periodically review execution times and remove redundant or disproportionately
expensive coverage. Do not add elaborate infrastructure solely to increase test counts.

## Evolve through evidence and observable contracts

Prefer incremental changes that preserve documented behavior and keep public APIs
small and straightforward. When a breaking change is justified, make its impact
and migration clear. Test observable outcomes, including failure and cleanup paths,
at the appropriate unit, integration or consumer boundary. Preserve module
independence in consumer verification.

Use focused experiments to answer real design questions, recording conditions and
limitations. Compare equivalent application behavior and distinguish whole-stack
results from an individual library's overhead. Small-server results depend on the
database, runtime, workload and available headroom; a smoke test alone does not
establish sustained reliability. Add testing infrastructure or complexity when
it addresses a concrete risk, and revisit implementation choices as evidence
changes.
