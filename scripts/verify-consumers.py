#!/usr/bin/env python3
"""Stage the complete reactor locally, then test consumers with empty Maven caches."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
output = root / "target" / "consumer-verification"
output.mkdir(parents=True, exist_ok=True)
run = Path(tempfile.mkdtemp(prefix="run-", dir=output))
staging = run / "repository"
staging.mkdir()
maven = shutil.which("mvn-lite") or shutil.which("mvn")
if not maven:
    raise SystemExit("Maven is required")


def execute(label, args, cwd):
    print(label, flush=True)
    log = run / (label + ".log")
    with log.open("w") as stream:
        result = subprocess.run([maven, *args], cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text())
        raise SystemExit(f"Failed: {label}; evidence: {run}")


# Build the producer in a copy so `clean` cannot remove this run’s evidence.
# Staging writes only to the scratch file repository, with local installation skipped.
producer = run / "producer"
shutil.copytree(root, producer, ignore=shutil.ignore_patterns("target", ".git"))
execute("stage-reactor", ["clean", "deploy", "-Dmaven.install.skip=true",
        "-DaltDeploymentRepository=verification::" + staging.as_uri()], producer)
for artifact in ("coilbase-parent", "coilbase-core", "coilbase-jex"):
    assert list((staging / "xyz/pvrlabs" / artifact).rglob("*.pom")), artifact

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
summary = {}
for module in ("core", "jex"):
    consumer = run / (module + "-consumer")
    shutil.copytree(root / "verification/consumers" / module, consumer)
    cache = run / (module + "-maven-cache")
    assert not cache.exists()
    execute(module + "-consumer", ["-Dmaven.repo.local=" + str(cache),
            "-Dcoilbase.repository=" + staging.as_uri(), "clean", "verify",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree",
            "-Dscope=runtime", "-DoutputFile=target/runtime-dependencies.txt"], consumer)
    # These consumers have no parent and are outside the producer reactor. Maven
    # must fetch the library parent from the staged repository to resolve them.
    parent = cache / "xyz/pvrlabs/coilbase-parent/0.1.0-preview1/coilbase-parent-0.1.0-preview1.pom"
    assert parent.is_file(), "Library parent was not resolved"
    tree = (consumer / "target/runtime-dependencies.txt").read_text()
    if module == "core":
        assert "com.fasterxml.jackson" not in tree and "io.avaje" not in tree, tree
    else:
        assert "jackson-datatype-jsr310:jar:2.21.5:compile" in tree, tree
    reports = [ET.parse(p).getroot() for p in (consumer / "target/surefire-reports").glob("TEST-*.xml")]
    assert reports and all(int(r.get("failures", 0)) + int(r.get("errors", 0)) + int(r.get("skipped", 0)) == 0 for r in reports)
    summary[module] = {"tests": sum(int(r.get("tests", 0)) for r in reports),
                       "parent_resolved": True, "runtime_dependencies": tree}
(run / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(f"PASS: complete local staging, parent resolution, Core isolation and Jex Java time JSON. Evidence: {run}")
