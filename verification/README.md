# Consumer verification

Run from the project root with JDK 21+, Maven and Python 3:

```sh
python3 scripts/verify-consumers.py
```

This builds and tests a temporary copy of the complete reactor, then deploys it
only to a temporary `file:` Maven repository. Local installation is skipped.
The staged artifacts include `xyz.pvrlabs:coilbase-parent` (POM), `coilbase-core`
and `coilbase-jex`; publishing only the two library JARs is insufficient because
consumers also need their POMs and the parent POM.

Two standalone consumer projects are copied outside the producer reactor. Each
starts with its own empty Maven local repository and resolves the staged library
and parent POM, downloading other dependencies and plugins as needed:

- Core checks API access and the absence of Jex, Jackson and Avaje HTTP classes
  and runtime dependencies.
- Jex declares only `coilbase-jex` plus test-scoped JUnit. It starts a real HTTP
  endpoint using the default application base and verifies ISO-8601 JSON strings
  for `Instant`, `LocalDate` and `LocalDateTime`, with no direct Java time module
  dependency in the consumer.

Logs, staged artifacts, consumer caches, runtime dependency trees and a JSON
summary are retained under `target/consumer-verification/run-*`. These consumer
projects are verification fixtures, not reactor modules or published artifacts.

For an eventual release, deploy the **complete root reactor**, including its
parent POM. No Central repository or credentials are configured here. This check
establishes local Maven repository resolution; it does not claim publication or
validate Central credentials, signing or release requirements.

## Validation recorded 2026-11-05

The complete run passed on JDK 25: 51 producer tests and both standalone consumer
tests, with no failures, errors or skips. Both consumers resolved the parent POM
from the staged repository into separate initially empty Maven caches. Jex
received `jackson-datatype-jsr310:2.21.5` transitively and returned the expected
ISO-8601 strings for all three Java time types. Core's runtime tree remained Jdbi,
SLF4J API and GeAnTyRef only. Producer class files retained Java 17 (Core) and
Java 21 (Jex) bytecode targets. No remote artifacts were published.
