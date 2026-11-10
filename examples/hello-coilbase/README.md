# Hello CoilBase

A small greetings API using annotated Avaje HTTP controllers, CoilBase Core,
Jex, HikariCP and an in-memory H2 database. It requires JDK 21+ and Maven; no external
database is needed.

**[Follow the complete Getting Started walkthrough](../../docs/getting-started.md)**
for API calls, wiring, transactions and configuration.

From the repository root, build and run the example:

```sh
mvn install
mvn -f examples/hello-coilbase/pom.xml clean verify
java -jar examples/hello-coilbase/target/hello-coilbase.jar
```

Keep `examples/hello-coilbase/target/lib/` beside the JAR. The server listens on
port 8080 by default; set `PORT=8081` to change it. Press Ctrl-C to stop.

This standalone example is separate from the library reactor and published JARs.
Licensed under [Apache-2.0](../../LICENSE).

The separate Native Image CI smoke test builds this example with GraalVM on Linux.
It records reachability metadata by running the same HTTP checks on the JVM, then
checks the native executable: the static welcome page, an empty list, a transactional
insert, invalid input, and reading back the committed greeting. It reports executable
size, approximate time to the first HTTP response, and RSS after readiness as informational values;
these are not benchmarks. Rollback is covered by the JVM repository integration
test, not by this HTTP smoke test. The smoke test uses in-memory H2 and does not
exercise PostgreSQL or other database configurations.
