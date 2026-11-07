# Application configuration

Configuration is plain Java owned by the application. CoilBase requires no
configuration model or DI container; applications can organize configuration
however they prefer.

This page is a work in progress. The intended direction is:

- Group related configuration values in an application-owned record such as
  `AppConfig`, with factories such as `development()`, `testing()` and `production()`.
- Choose values at the entrypoint and keep environment-specific decisions out of services.
- Construct dependencies explicitly through constructors.
- Replace the primary datasource in tests through the existing `createPrimaryDataSource()` hook.

Planned `ConfigProperties` support would allow individual values to be overridden
through environment variables, JVM system properties or explicit runtime overrides.
The API, precedence rules and runtime update semantics are still to be designed.
In particular, changing a configuration value would not automatically reconfigure
an existing datasource or other constructed dependency; that behavior would need
to be defined explicitly. These are design intentions, not APIs available today.

For capabilities available today, see [Database configuration](database-configuration.md)
and the lifecycle hooks in
[`CoilBaseJexApplication`](../coilbase-jex/src/main/java/pvrlabs/coilbase/jex/CoilBaseJexApplication.java).
