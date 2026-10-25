#!/usr/bin/env python3
"""Check resolved Maven runtime versions against OSV; network/schema errors fail closed."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

API = "https://api.osv.dev/v1/querybatch"


def dependencies(path):
    packages = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        coordinate = line.strip().split(" -- ", 1)[0]
        parts = coordinate.split(":")
        if len(parts) in {5, 6} and parts[2] == "jar" and parts[-1] in {"compile", "runtime"}:
            if not all(re.fullmatch(r"[A-Za-z0-9_.+\-]+", item) for item in parts):
                raise ValueError("Unsupported Maven coordinate in resolved dependency inventory.")
            packages.add((parts[0] + ":" + parts[1], parts[-2]))
    if not packages:
        raise ValueError("Resolved runtime dependency inventory is empty; run Maven dependency:list first.")
    return sorted(packages)


def query_batch(queries):
    request = urllib.request.Request(API, data=json.dumps({"queries": queries}).encode(), headers={
        "Content-Type": "application/json", "User-Agent": "HackAtlas-runtime-dependency-check/1",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    results = payload.get("results")
    if not isinstance(results, list) or len(results) != len(queries):
        raise ValueError("OSV returned an incomplete batch; dependency check cannot pass.")
    return results


def audit(packages):
    findings = {package: set() for package in packages}
    pending = [(package, {"package": {"ecosystem": "Maven", "name": package[0]}, "version": package[1]})
               for package in packages]
    seen = set()
    while pending:
        batch, pending = pending[:100], pending[100:]
        for (package, query), result in zip(batch, query_batch([item[1] for item in batch]), strict=True):
            if not isinstance(result, dict) or "error" in result or not isinstance(result.get("vulns", []), list):
                raise ValueError("Unexpected OSV response shape.")
            for vulnerability in result.get("vulns", []):
                identifier = vulnerability.get("id")
                if not isinstance(identifier, str) or not identifier:
                    raise ValueError("OSV vulnerability has no identifier.")
                findings[package].add(identifier)
            token = result.get("next_page_token")
            if token:
                if not isinstance(token, str):
                    raise ValueError("Invalid OSV pagination token.")
                key = (package, token)
                if key in seen or len(seen) >= 1000:
                    raise ValueError("Invalid or excessive OSV pagination; refusing partial results.")
                seen.add(key)
                pending.append((package, {**query, "page_token": token}))
    return [{"package": name, "version": version, "vulnerabilities": sorted(identifiers)}
            for (name, version), identifiers in findings.items() if identifiers]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    packages = dependencies(args.dependencies)
    findings = audit(packages)
    report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "source": API,
              "scope": "Resolved Maven runtime JAR versions only; not OS/JRE/container or reachability analysis",
              "packagesChecked": len(packages), "findings": findings}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OSV checked {len(packages)} resolved Maven runtime packages; affected packages: {len(findings)}.")
    for item in findings:
        print(item["package"] + ":" + item["version"] + " : " + ", ".join(item["vulnerabilities"]))
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, urllib.error.URLError) as error:
        # Do not forward proxy URLs, arbitrary HTTP response bodies or request headers.
        sys.exit("Dependency check did not complete: " + type(error).__name__)
