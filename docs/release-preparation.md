# Preview release preparation

The candidate version is `0.1.0-preview1`. The intended distribution channel is
[PVR Labs Maven](https://pvrlabs.github.io/maven/), using its GitHub Release
asset submission process. This document prepares a reviewable candidate; it does
not authorize a release or changes to public repositories.

Build the parent reactor with Java 21 and run the separate Hello, metrics and
consumer verification projects. Run Core tests separately on Java 17. The
consumer verification script stages the reactor into a temporary Maven layout
and resolves both independent consumers with initially empty caches.

The required release assets are:

| Coordinate | Assets |
| --- | --- |
| `xyz.pvrlabs:coilbase-parent:0.1.0-preview1` | parent POM |
| `xyz.pvrlabs:coilbase-core:0.1.0-preview1` | module POM and JAR |
| `xyz.pvrlabs:coilbase-jex:0.1.0-preview1` | module POM and JAR |

Inspect the consumer POMs and their parent and dependency resolution before
choosing the exact files for release. Source JARs are optional for the PVR Labs
repository and are built by the reactor. Verify selected assets against the
tested build, calculate SHA-256 digests, and compare those digests to the
eventual uploaded assets. Then prepare URL and digest pointers according to the
Maven repository's publishing guide. After publication, test resolution from
the public URL with a fresh Maven cache.

Before publication, complete a clean standalone checkout, public CI, API and
license reviews, and final candidate approval. The local README retains build-from-source instructions
until the hosted artifacts actually exist.
