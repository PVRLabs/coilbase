# Runtime comparisons

An **exploratory snapshot of complete applications**, not a formal framework
benchmark, universal ranking or measurement of CoilBase's own overhead. The
maintainer also authors CoilBase.

## Recorded snapshot — October–November 2026

**S:** matched five-stack quote/status/audit application (reads, inserts and atomic
status/audit writes). **M:** earlier Market Replay application (scheduled ingestion,
latest/history reads and browser UI). Neither includes portfolio editing.
Compare within each workload; S and M are different applications. S cells show
**run 1 / run 2**, in that order; M cells are single accepted runs.

| Workload | Application | Runtime / deployment | Startup / readiness (s) | Workload median app RSS (MiB) | HTTP p95 (ms) |
| --- | --- | --- | ---: | ---: | ---: |
| S | Spring Boot + Spring JDBC | HotSpot JDK 27, G1 | 7.425 / 8.653 | 180.6 / 187.9 | 7.56 / 6.10 |
| S | Javalin + CoilBase | HotSpot JDK 27, G1 | 3.063 / 2.920 | 135.6 / 120.9 | 6.74 / 6.07 |
| S | Micronaut Data JDBC | HotSpot JDK 27, G1 | 4.397 / 4.345 | 175.0 / 174.0 | 7.38 / 6.31 |
| S | Micronaut + CoilBase | HotSpot JDK 27, G1 | 4.248 / 5.275 | 166.5 / 167.6 | 7.22 / 7.49 |
| S | Jex + CoilBase | HotSpot JDK 27, G1 | 2.022 / 2.543 | 116.0 / 116.4 | 6.11 / 6.03 |
| S | Spring Boot + Spring JDBC | GraalVM Native Image JDK 25, Serial, `-O2`, Spring AOT | 0.172 / 0.273 | 84.0 / 85.0 | 5.34 / 6.26 |
| S | Javalin + CoilBase | GraalVM Native Image JDK 25, Serial, `-O2` | 0.078 / 0.172 | 59.2 / 58.3 | 4.93 / 5.80 |
| S | Micronaut Data JDBC | GraalVM Native Image JDK 25, Serial, `-O2` | 0.188 / 0.175 | 89.7 / 91.7 | 5.02 / 5.04 |
| S | Micronaut + CoilBase | GraalVM Native Image JDK 25, Serial, `-O2` | 0.211 / 0.212 | 82.4 / 81.8 | 5.35 / 5.43 |
| S | Jex + CoilBase | GraalVM Native Image JDK 25, Serial, `-O2` | 0.058 / 0.076 | 33.4 / 51.0 | 5.33 / 4.81 |
| M | Standard HotSpot A | Spring Boot, JDK 27, G1, ordinary executable JAR | 11.91 | 212.0 | 5.34 |
| M | Optimized HotSpot F | Spring Boot, JDK 27, G1, extracted layout + Spring AOT + trained JDK AOT cache | 3.789 | 186.9 | 5.54 |
| M | Oracle Native Image | Spring Boot, GraalVM JDK 25, Serial, optimization 2, Spring AOT | 0.183 | 107.0 | 4.84 |
| M | Javalin `javalin-002` | HotSpot JDK 27, G1, trained JDK AOT cache | 2.724 | 159.08 | 5.59 |
| M | Native `-Ob` (`native-001`) | Javalin, GraalVM JDK 25, Serial, quick-build | 0.112 | 59.41 | 4.03 |
| M | Native `-O2` (`native-o2-001`) | Javalin, GraalVM JDK 25, Serial | 0.113 | 65.50 | 4.34 |

App RSS is the median of samples **within each workload run**, not peak RSS, heap,
database memory or total host consumption. MiB = 1,048,576 bytes. HTTP p95 is
per run over successful workload requests; distributions are not pooled.
S readiness ends at a successful database-backed latest response (~100 ms polling);
M requires health UP plus three persisted quotes (~50 ms polling). Displayed
precision follows the source reports, not timing accuracy.

## Conditions and limits

Ubuntu 24.04 x86_64 local VMs: one vCPU, 512 MiB allocated RAM (~456 MiB
Linux-visible), no swap, app and MariaDB in the same budget; M also runs a Python
quote fixture. Hikari pool 1/4; MariaDB InnoDB/key/Aria caches 64/8/16 MiB, UTC,
normal durability. HotSpot: OpenJDK 27+35-2325/G1; native: Oracle GraalVM
25.0.4+7.1/Serial. All have an 80 MiB maximum heap guard; S also sets 16 MiB
initial heap. S HotSpot uses no framework AOT or trained cache.

Fresh app/schema state and warm storage precede 60 s idle, 120 s warm-up,
300 s workload and 60 s recovery. Offered load: 5 requests/s, four maximum in
flight, no retries. S repeats 8 reads, 1 insert, 1 status/audit transaction;
M alternates latest/history while ingestion continues. Each accepted run served
1,500 workload requests without errors/timeouts or OOM/swap. OS sampling is
approximately one second; latency includes host/SSH forwarding.

Two S runs and single M runs establish no confidence interval, capacity, minimum
RAM or long-term reliability. JDKs, collectors, framework AOT, monitoring,
dates/boots and OS patches differ. Some first-round S native runs overlapped
builder activity on the physical host; later runs had it stopped. Small latency
differences and `-Ob`/`-O2` ordering should not be generalized. Native compilation
also costs minutes: S generation took 6m27s–13m03s on a separate two-vCPU/6-GiB
builder, excluding packaging/tests.

## Sources and future updates

S comprises two accepted HotSpot runs per stack on October 30 and two native
runs per stack completed by November 4, 2026; its public data release is pending.
Article 3's link will be added when its public URL is available.

Published M results and methods:
[Spring Boot A/F](https://github.com/PVRLabs/experiments/blob/main/spring/boot-jdk27/README.md),
[Spring Boot Native Image](https://github.com/PVRLabs/experiments/blob/main/spring/boot-jdk27/oracle-native-image/README.md),
[Javalin HotSpot](https://github.com/PVRLabs/experiments/blob/main/javalin/market-replay-jdk27/README.md),
[Javalin Native Image](https://github.com/PVRLabs/experiments/blob/main/javalin/market-replay-native/README.md).
Database/persistence follow-ups, monitoring runs and development-host smokes are
outside this runtime snapshot.

This URL is the permanent comparison reference. A dedicated formal benchmark can
replace or supplement this snapshot here, with its own method, application
identity and evidence links.
