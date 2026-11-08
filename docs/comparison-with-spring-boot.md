# CoilBase vs Spring Boot

## Different scope, familiar architecture

CoilBase keeps an application shape Spring Boot developers already know: MVC-style controllers, services for business
logic, repositories for data access, constructor wiring, and explicit transaction boundaries.

CoilBase complements existing Java frameworks rather than replacing them. It keeps those Spring-style patterns and uses
lighter implementations. Core holds repositories, services, SQL, and transaction boundaries, so business logic can be
reused across hosts, including Spring Boot, with adaptation at the framework boundary. Routes and bootstrap stay with the
host. Optional `coilbase-jex` already supplies startup, JSON, error handling, and lifecycle conveniences for Avaje Jex,
without hiding Jex's own APIs. [Framework integrations](framework-integrations.md) describes what stays portable.

## At a glance

| Area | Spring Boot | CoilBase |
| --- | --- | --- |
| Application structure | Container-assembled layers | Repositories, services, and controllers wired in application code |
| Dependency injection | Container injection and scanning | Constructor wiring. No container is included |
| Auto-configuration | Starters and conditional infrastructure | None. The application constructs its dependencies |
| HTTP | Spring MVC or WebFlux | Host routing. `coilbase-jex` adds startup, JSON, errors, and lifecycle |
| Persistence | JDBC, JPA, or Spring Data | SQL-first Jdbi, with optional dialect overrides |
| Transactions | Declarative `@Transactional`, propagation and rollback rules | `transactional` on one datasource and thread. Nested work joins it |
| Configuration | Property binding and profiles | Application-owned Java objects and records; explicit environment and system property access |
| Testing | Spring Test, context and slice tests | Plain Java unit tests, `TestRepositoryContext`, JDBC integration tests |

Wiring, SQL, and configuration status are in [Getting started](getting-started.md),
[Database configuration](database-configuration.md), and
[Application configuration](application-configuration.md).

## Explicit wiring

Automatic configuration is convenient: common libraries connect with little application code. It also leaves behavior
implicit. Conditional dependencies decide what gets created, and tracing those decisions becomes debugging and maintenance
work.

CoilBase does not build that machinery. For the applications it targets, those costs are not justified. Collaborators are
ordinary Java constructors. Datasource setup, SQL dialect, route registration, and error mappings stay at the composition
root. Startup order and resource ownership stay visible. The Jex base closes an `AutoCloseable` primary datasource on
shutdown or startup failure. IDE navigation follows normal Java references, with no component scan required.

In a larger application that repetition can matter. A managed container is a reasonable fit when shared scopes and a wide
set of conditional infrastructure are the goal. CoilBase is for applications that prefer the wiring to remain in source.

## Familiar MVC-style controllers

MVC-style controllers are one of the Spring Boot conveniences CoilBase intentionally preserves. A smaller runtime does not
require giving up the controller → service → repository organization.

Controllers handle HTTP concerns, services contain business logic, and repositories manage persistence. The application
connects those classes explicitly.

Core does not implement MVC. The recommended Jex integration uses Avaje HTTP annotation-based controllers, such as
`@Controller`, `@Get`, and `@Post`. Route adapters are generated at compile time and registered explicitly. No runtime
component scanning or DI container is required. [Getting started](getting-started.md) shows this in Hello CoilBase.

Spring's controllers are discovered and managed at runtime. Here the same layering stays in ordinary classes, and the HTTP
framework remains the lightweight one the application chose. Other HTTP frameworks remain viable, with their own routing
and controller conventions.

## Dependency injection is optional

Constructor wiring is the default, not a rejection of dependency injection. Those classes can sit behind an existing
container. CoilBase prescribes none. A lightweight integration may be evaluated later; that is not a committed feature, and
Core ships no DI runtime.

## Configuration

Configuration is application-owned Java, usually objects or records. A small application can hardcode values or select
`development()`, `testing()`, or `production()` at the entrypoint. Reading environment variables and JVM system properties
is ordinary Java, and needs no CoilBase API.

Planned, and not shipped, is a standardized `ConfigProperties` abstraction over those sources, potentially with optional
runtime updates through an application-defined interface. Changing a property would not itself rebuild a datasource or any
other object already constructed. See [Application configuration](application-configuration.md).

## Selective integrations

CoilBase adds a few conveniences on libraries that already do the work.

**[Jdbi](https://jdbi.org/)** is the SQL-first persistence foundation, chosen after alternatives were evaluated.
CoilBase adds repository helpers, dialect selection, and transaction boundaries.

**[Avaje Jex](https://avaje.io/jex/)**, through `coilbase-jex`, is the preferred HTTP integration.
[Javalin](https://javalin.io/) was used in earlier experiments and remains a viable alternative. The preference
follows current needs and recorded evidence, not a universal or permanent ranking. See
[Framework integrations](framework-integrations.md) and [Runtime comparisons](benchmarks.md).

## Choices, limits, and preview scope

An ORM and generated repositories are outside the SQL-first design. Distributed transactions and advanced propagation are
unsupported.

Other gaps are preview scope, not a permanent refusal. Integrated authentication and authorization are outside the initial
release; suitable libraries are still under evaluation. Observability is experimental StatLite: basic request and JVM
counters, plus an explicit database probe. See [StatLite monitoring](statlite-monitoring.md).

Versioned migrations are a separate choice. They stay with Flyway or Liquibase. Script helpers run SQL only when the
application calls them. See [Database configuration](database-configuration.md).

## Where CoilBase fits

CoilBase is not intended to replace Spring Boot. It brings familiar controller, service, repository, and transaction
patterns to lightweight Java applications without requiring the full Spring Boot runtime.

This is particularly useful for small services running on resource-constrained infrastructure, including small VPS
instances, where framework overhead can make a meaningful difference. See the
[runtime comparisons](benchmarks.md) for measured examples.

Applications can retain portable business logic and familiar organization while using a smaller HTTP stack. Those same
components can also be adapted to Spring Boot as requirements evolve.

Spring Boot remains a natural choice when its broader ecosystem and managed infrastructure are needed. The goal is not to
replace it, but to offer a lighter foundation for applications that don't need all of it.
