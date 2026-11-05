# Hello CoilBase

A small greetings API using annotated Avaje HTTP controllers, CoilBase Core,
Jex and an in-memory H2 database. It requires JDK 21+ and Maven; no external
database is needed.

**[Follow the complete Getting Started walkthrough](../../docs/getting-started.md)**
for API calls, wiring, transactions and configuration.

From the repository root, build and run the example. Its POM resolves CoilBase
from the public PVR Labs Maven repository:

```sh
mvn -f examples/hello-coilbase/pom.xml clean verify
java -jar examples/hello-coilbase/target/hello-coilbase-0.1.0-preview1.jar
```

Keep `examples/hello-coilbase/target/lib/` beside the JAR. The server listens on
port 8080 by default; set `PORT=8081` to change it. Press Ctrl-C to stop.

This standalone example is separate from the library reactor and published JARs.
Licensed under [Apache-2.0](../../LICENSE).
