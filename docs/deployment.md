# Deployment

Hello CoilBase illustrates a small, standalone application: build a thin JAR and
its runtime dependencies, then run them together on a Linux host. The example uses
an in-memory H2 database and loses its data on exit; adapt its datasource and
configuration for a real application. See the public [CoilBase PetClinic reference
application](https://github.com/ejboy/coilbase-petclinic) for a larger example.

## Maven repository access

CoilBase preview artifacts are hosted in the PVR Labs Maven repository, not Maven
Central. If policy disallows that repository, clone the matching CoilBase release
and run `mvn install` locally. For reproducible team or CI builds, publish the
approved artifacts, including the parent POM, to an internal Maven repository.
Repository access is a build-time concern; the application does not need it at runtime.

## Package and run on the JVM

With JDK 21+ and Maven, from the checkout root:

```sh
mvn install
mvn -f examples/hello-coilbase/pom.xml clean verify
mkdir -p /tmp/hello-deploy
tar -xzf examples/hello-coilbase/target/hello-coilbase-distribution.tar.gz -C /tmp/hello-deploy
cd /tmp/hello-deploy/hello-coilbase
PORT=8080 java -jar hello-coilbase.jar
```

The archive contains the executable JAR and `lib/`; keep them together because
the manifest references the dependencies by relative path. Copy the extracted
directory to the server and use a compatible Java runtime there. Maven and access
to its repositories are only needed for the build.

On a small VM, leave memory for the OS, database and other processes as well as
the JVM. Heap is only part of process memory: native allocations, threads and
buffers also consume memory. Start with defaults or a measured heap limit, observe
RSS and behavior under representative load, and adjust with headroom; no single
heap size is appropriate for every application.

## Run as a service

For example, install the extracted files in `/opt/hello-coilbase`, create an
unprivileged `hello` user with read access to them, and place configuration in
`/etc/hello-coilbase.env` (for example, `PORT=8080`). Restrict access to that
file if it contains secrets. A minimal `/etc/systemd/system/hello-coilbase.service`:

```ini
[Unit]
Description=Hello CoilBase
After=network.target

[Service]
User=hello
WorkingDirectory=/opt/hello-coilbase
EnvironmentFile=/etc/hello-coilbase.env
ExecStart=/usr/bin/java -jar /opt/hello-coilbase/hello-coilbase.jar
Restart=on-failure
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Run `sudo systemctl daemon-reload` and `sudo systemctl enable --now hello-coilbase`;
inspect logs with `journalctl -u hello-coilbase`. Adjust the Java path and file
permissions to suit the host. See [application configuration](application-configuration.md)
and [database configuration](database-configuration.md) for application-specific setup.

## Native Image

Use GraalVM with Native Image and a native toolchain on the target platform. From
the checkout root, the existing [native smoke workflow](../.github/workflows/native-smoke.yml)
builds the example, collects reachability metadata by exercising its JVM paths,
then compiles and checks the executable:

```sh
mvn -DskipTests install
mvn -f examples/hello-coilbase/pom.xml -DskipTests package
python3 .github/scripts/native-smoke.py java \
  -agentlib:native-image-agent=config-output-dir=examples/hello-coilbase/target/native-agent \
  -jar examples/hello-coilbase/target/hello-coilbase.jar
mvn -f examples/hello-coilbase/pom.xml -Pnative -DskipTests native:compile
python3 .github/scripts/native-smoke.py examples/hello-coilbase/target/hello-coilbase
```

The `native` profile reads the generated `target/native-agent` metadata and enables
the GraalVM metadata repository. Collect metadata before compiling, and exercise all
relevant application paths and configurations: the example smoke covers selected
H2 paths, not every dependency or database. Maintain any additional metadata needed
by your application and verify the resulting executable. Copy the executable to a
compatible Linux host; run it directly or change the service `ExecStart` to its
absolute path. Native builds are platform-specific and do not require a JVM at runtime.

## Smoke and health checks

After startup, `curl -f http://localhost:8080/greetings` should return an empty
JSON list for a fresh example. The [Getting Started walkthrough](getting-started.md#2-call-the-api)
also checks an insert and validation. This endpoint is an application smoke check,
not a dedicated health endpoint. For optional health and metrics integration, see
[StatLite monitoring](statlite-monitoring.md).
