#!/usr/bin/env python3
"""Scan exact rehearsed images offline, then enforce the external release gate."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import uuid

from container_policy import SCANNER_IMAGE, SCANNER_VERSION, main as check_policy

ROOT = Path(__file__).resolve().parents[2]


def run(command, *, output=None, timeout=240):
    if output:
        with output.open("wb") as stdout, output.with_suffix(output.suffix + ".log").open("wb") as stderr:
            result = subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr, timeout=timeout)
    else:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
    if result.returncode:
        # Only tool/version/public image commands run here; no application secrets are loaded.
        raise RuntimeError("Command failed: " + command[0] + " " + command[1] + "; inspect audit logs")
    return output.read_bytes() if output else result.stdout


def config_digest(archive):
    with tarfile.open(archive) as source:
        entries = json.load(source.extractfile("manifest.json"))
        if len(entries) != 1:
            raise ValueError("Expected one image in Docker archive")
        member = source.getmember(entries[0]["Config"])
        if not member.isfile() or member.size > 1024 * 1024:
            raise ValueError("Invalid archived image configuration")
        return "sha256:" + hashlib.sha256(source.extractfile(member).read()).hexdigest()


def cleanup_cache(cache):
    containers = run(["docker", "ps", "--all", "--quiet", "--filter", "label=hackatlas.audit=" + cache]).decode().split()
    for identifier in containers:
        item = json.loads(run(["docker", "inspect", identifier]))[0]
        if item["Config"].get("Labels", {}).get("hackatlas.audit") != cache:
            raise ValueError("Refusing cleanup of unowned scanner container")
        run(["docker", "rm", "--force", identifier])
    labels = json.loads(run(["docker", "volume", "inspect", cache]))[0].get("Labels", {})
    if labels.get("hackatlas.audit") != cache:
        raise ValueError("Refusing cleanup of unowned scanner cache")
    run(["docker", "volume", "rm", cache])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rehearsal-report", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True, help="New evidence directory; never overwritten")
    parser.add_argument("--cache-volume", help="Resume an owned hackatlas-audit-<uuid> cache left by an interrupted run")
    args = parser.parse_args()
    if os.name != "posix":
        raise ValueError("Run on Linux/WSL with the same Docker daemon as the rehearsal")
    rehearsal = json.loads(args.rehearsal_report.read_text(encoding="utf-8"))
    if rehearsal.get("result") != "PASS" or not rehearsal.get("cleanup", {}).get("containersAndVolumesRemoved"):
        raise ValueError("A successful, cleaned-up rehearsal is required")
    project = rehearsal["project"]
    if not re.fullmatch(r"hackatlas-b4-[0-9a-f]{12}", project):
        raise ValueError("Unexpected rehearsal project identity")
    postgres = set(re.findall(r"image: (postgres:[^\s]+)", (ROOT / "infra/release/compose.yaml").read_text()))
    if len(postgres) != 1:
        raise ValueError("Expected one pinned release PostgreSQL image")
    references = {name: rehearsal["images"][name]["tag"] for name in ("app", "proxy")}
    for name, reference in references.items():
        if reference != project + "-" + name + ":local":
            raise ValueError("Unexpected rehearsed image reference")
    references["postgres"] = postgres.pop()
    if rehearsal["images"].get("postgres", {}).get("tag") != references["postgres"]:
        raise ValueError("PostgreSQL reference changed since rehearsal; rerun the candidate")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cache = args.cache_volume or "hackatlas-audit-" + uuid.uuid4().hex
    if not re.fullmatch(r"hackatlas-audit-[0-9a-f]{32}", cache):
        raise ValueError("Invalid audit cache identity")
    cache_created = False
    archives = []
    manifest = {"formatVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
                "rehearsalProject": project, "jarSha256": rehearsal["jarSha256"],
                "scannerImage": SCANNER_IMAGE, "images": {}}
    try:
        run(["docker", "pull", SCANNER_IMAGE], output=output / "scanner-pull.log")
        version = run(["docker", "run", "--rm", "--network", "none", SCANNER_IMAGE, "--version"]).decode()
        if "Version: " + SCANNER_VERSION not in version:
            raise ValueError("Unexpected scanner version")
        if args.cache_volume:
            labels = json.loads(run(["docker", "volume", "inspect", cache]))[0].get("Labels", {})
            if labels.get("hackatlas.audit") != cache:
                raise ValueError("Refusing reuse of unowned scanner cache")
        else:
            run(["docker", "volume", "create", "--label", "hackatlas.audit=" + cache, cache])
        cache_created = True
        base = ["docker", "run", "--rm", "--label", "hackatlas.audit=" + cache, "--read-only", "--cap-drop", "ALL", "--security-opt",
                "no-new-privileges", "-v", cache + ":/cache"]
        run(base + ["--network", "none", "--entrypoint", "mkdir", SCANNER_IMAGE, "-p", "/cache/tmp"])
        for kind, timeout in (("db", "3m"), ("java-db", "5m")):
            print("Prepare scanner " + kind + " cache", flush=True)
            run(base + ["-e", "TMPDIR=/cache/tmp", SCANNER_IMAGE, "image", "--cache-dir", "/cache",
                        "--timeout", timeout, "--no-progress", "--download-" + kind + "-only"],
                output=output / (kind + "-download.log"), timeout=600)
        for name, directory in (("database", "db"), ("javaDatabase", "java-db")):
            raw = run(base + ["--network", "none", "--entrypoint", "cat", SCANNER_IMAGE,
                              "/cache/" + directory + "/metadata.json"])
            manifest[name] = json.loads(raw)
        for name, reference in references.items():
            inspect = json.loads(run(["docker", "image", "inspect", reference]))[0]
            if inspect["Id"] != rehearsal["images"][name]["id"]:
                raise ValueError("Image changed since successful rehearsal")
            archive = output / (name + ".tar")
            archives.append(archive)
            run(["docker", "image", "save", "--output", str(archive), reference])
            with archive.open("rb") as stream:
                archive_hash = hashlib.file_digest(stream, "sha256").hexdigest()
            image = {"reference": reference, "inspectedImageId": inspect["Id"],
                     "configDigest": config_digest(archive), "archiveSha256": archive_hash}
            if rehearsal["images"][name].get("containerImageId") not in (inspect["Id"], image["configDigest"]):
                raise ValueError("Archive does not match the container used in the rehearsal")
            if name == "app":
                raw = run(["docker", "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL",
                           "--security-opt", "no-new-privileges", "--entrypoint", "java", reference, "-version"],
                          output=output / "java-version.log")
                # java -version uses stderr, recorded beside stdout by run().
                version_text = raw.decode() + (output / "java-version.log.log").read_text()
                match = re.search(r"Temurin-(21\.0\.\d+(?:\.\d+)?\+\d+)", version_text)
                if not match:
                    raise ValueError("Cannot identify the actual Temurin runtime")
                image["javaRuntimeVersion"] = match[1]
            report_path = output / (name + "-trivy.json")
            run(base + ["--network", "none", "--tmpfs", "/tmp:rw,nosuid,nodev,size=1g",
                        "-v", str(archive) + ":/input/image.tar:ro", SCANNER_IMAGE, "image",
                        "--cache-dir", "/cache", "--timeout", "3m", "--skip-db-update", "--skip-java-db-update",
                        "--offline-scan", "--no-progress", "--scanners", "vuln", "--format", "json",
                        "--input", "/input/image.tar"], output=report_path)
            image["reportSha256"] = hashlib.sha256(report_path.read_bytes()).hexdigest()
            manifest["images"][name] = image
            print("Scanned " + name + " " + image["configDigest"], flush=True)
        (output / "audit-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        sys.argv = [sys.argv[0], "--evidence", str(output)]
        return check_policy()
    finally:
        failure_in_progress = sys.exc_info()[0] is not None
        if cache_created:
            try:
                cleanup_cache(cache)
            except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
                print("Scanner cleanup incomplete; owned cache: " + cache, file=sys.stderr)
                if not failure_in_progress:
                    raise RuntimeError("Scanner cleanup failed; candidate is not approved")
        for archive in archives:
            if archive.is_file():
                archive.unlink()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print("Container audit incomplete: " + type(error).__name__ + ": " + str(error), file=sys.stderr)
        sys.exit(2)
