# Releasing CoilBase artifacts

Use one immutable version across the parent, core, Jex, examples and consumer
fixtures. Preview tags use the Maven version, for example `0.1.0-preview2`.

1. Update versions, release notes and examples. Run `mvn-lite clean verify`,
   the example and metrics verification projects, and
   `python3 scripts/verify-consumers.py`. The consumer check stages the complete
   reactor and resolves it from separate projects with empty Maven caches.
2. Commit the release source and documentation, push it, and tag that exact
   commit. Create a GitHub prerelease in `PVRLabs/coilbase` with release notes.
   Upload these seven files, using the chosen version in every filename:
   `coilbase-parent-VERSION.pom`, and the POM, JAR and sources JAR for each of
   `coilbase-core` and `coilbase-jex`. Copy original project POMs to the matching
   release filenames; retain the bytes of all tested artifacts.
3. Compare each uploaded asset's SHA-256 digest with the local file. Never replace
   assets or reuse a published version. Fixes require a new version.
4. Locate `PVRLabs/maven-repo` with RepoLink when registered, or use its existing
   checkout. Start a submission branch from current `main`. Add one pointer per
   asset at `artifacts/xyz/pvrlabs/ARTIFACT/VERSION/FILENAME.url`. Each pointer
   contains the public GitHub release download URL and its SHA-256 digest on
   separate lines. Include the parent POM and both module POMs.
5. Open a PR linking the source release and identifying the Apache-2.0 license.
   Review coordinates, required assets, provenance and digests, then merge.
   Submission PRs contain pointers only; do not edit `published.json` there.
6. Update the Maven source checkout to merged `main`. Locate the hosting checkout
   with `repolink get pvrlabs.github.io` and ensure both working trees are clean.
   From `maven-repo`, run
   `./scripts/publish.sh /path/to/pvrlabs.github.io/maven`. This verifies downloads,
   preserves artifact bytes, generates checksums and Maven metadata, and updates
   the authoritative `maven-repo/published.json`.
7. Review and commit publication state in `maven-repo`; review and commit generated
   Maven files in `pvrlabs.github.io`. Update its Maven index when necessary so
   the release is discoverable. Push both commits and wait for GitHub Pages.
8. Verify the public files and checksums at `https://pvrlabs.github.io/maven/`.
   Repeat standalone consumer resolution against that URL with a fresh Maven
   cache. Record the release, submission PR, publication commits and validation
   results. Publication is complete only after public consumer verification.

The Maven repository's `CONTRIBUTING.md`, `docs/publishing-a-library.md` and
`docs/publishing.md` describe its pointer format and publisher behavior. Published
versions are immutable; the publisher runs incrementally against the existing
hosting directory and publication state.
