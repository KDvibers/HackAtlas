#!/usr/bin/env python3
"""Fail closed on incomplete container evidence or unresolved release blockers."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

SCANNER_VERSION = "0.76.1"
SCANNER_IMAGE = "aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c"
SERVICES = {"app": "jar", "proxy": "gobinary", "postgres": "gobinary"}
SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


class PolicyError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PolicyError(message)


def fresh(value, now, maximum_age, label):
    try:
        stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(stamp.utcoffset() is not None, label + " has no timezone")
        require(timedelta(minutes=-5) <= now - stamp <= maximum_age, label + " is stale or in the future")
    except (AttributeError, TypeError, ValueError) as error:
        raise PolicyError(label + " must be a recent ISO-8601 timestamp") from error


def evaluate_report(report, expected_config_digest, *, now):
    require(isinstance(report, dict) and report.get("SchemaVersion") == 2, "Unsupported Trivy report")
    require(report.get("ArtifactType") == "container_image", "Expected a container image report")
    require(report.get("Trivy", {}).get("Version") == SCANNER_VERSION, "Unexpected scanner version")
    require(DIGEST.fullmatch(expected_config_digest or ""), "Invalid expected config digest")
    require(report.get("Metadata", {}).get("ImageID") == expected_config_digest, "Report/image config mismatch")
    fresh(report.get("CreatedAt"), now, timedelta(hours=24), "Report timestamp")
    results = report.get("Results")
    require(isinstance(results, list) and results, "Empty scanner results")
    require(all(isinstance(r, dict) for r in results), "Malformed result")
    require(any(r.get("Class") == "os-pkgs" and r.get("Packages") for r in results), "OS inventory missing")
    counts = dict.fromkeys(sorted(SEVERITIES), 0)
    findings, types, unversioned, caddy_module = [], set(), [], None
    for result in results:
        require(isinstance(result, dict) and isinstance(result.get("Packages"), list), "Package inventory missing")
        require(all(isinstance(p, dict) and isinstance(p.get("Name"), str) and p["Name"]
                    and (p.get("Version") is None or isinstance(p["Version"], str)) for p in result["Packages"]),
                "Incomplete package inventory")
        unversioned.extend({"Name": p["Name"], "Type": result.get("Type"),
                            "purl": (p.get("Identifier") or {}).get("PURL")}
                           for p in result["Packages"] if not p.get("Version"))
        for package in result["Packages"]:
            if (result.get("Type") == "gobinary" and package["Name"] == "github.com/caddyserver/caddy/v2"
                    and re.fullmatch(r"v2\.\d+\.\d+", package.get("Version") or "")):
                caddy_module = package["Version"]
        require(not result.get("Error") and not result.get("Errors"), "Scanner reported errors")
        if result["Packages"]:
            types.add(result.get("Type"))
        vulnerabilities = result.get("Vulnerabilities", [])
        require(isinstance(vulnerabilities, list), "Malformed vulnerability list")
        for item in vulnerabilities:
            require(isinstance(item, dict), "Malformed vulnerability")
            require(all(isinstance(item.get(k), str) and item[k] for k in
                        ("VulnerabilityID", "PkgName", "InstalledVersion")), "Finding identity missing")
            severity = item.get("Severity")
            require(severity in SEVERITIES, "Unclassified severity")
            counts[severity] += 1
            findings.append({k: item.get(k) for k in ("VulnerabilityID", "PkgName", "InstalledVersion",
                                                     "FixedVersion", "Severity", "Status")})
    return {"severityCounts": counts, "findings": findings, "unversionedPackages": unversioned,
            "versionedCaddyModule": caddy_module, "types": sorted(t for t in types if t)}


def evaluate_evidence(manifest, reports, *, now):
    require(manifest.get("formatVersion") == 1, "Unsupported evidence format")
    fresh(manifest.get("createdAt"), now, timedelta(hours=24), "Evidence timestamp")
    require(manifest.get("scannerImage") == SCANNER_IMAGE, "Unexpected scanner image")
    require(set(manifest.get("images", {})) == set(SERVICES) == set(reports), "Expected app/proxy/postgres evidence")
    for key, age, version in (("database", timedelta(hours=48), 2), ("javaDatabase", timedelta(days=7), 1)):
        metadata = manifest.get(key)
        require(isinstance(metadata, dict) and metadata.get("Version") == version, "Database metadata missing or unsupported")
        fresh(metadata.get("UpdatedAt"), now, age, key + " timestamp")
    blockers, images = [], {}
    for name, language in SERVICES.items():
        evidence = manifest["images"][name]
        require(DIGEST.fullmatch(evidence.get("inspectedImageId", "")), "Inspected image identity missing")
        require(re.fullmatch(r"[0-9a-f]{64}", evidence.get("archiveSha256", "")), "Archive hash missing")
        summary = evaluate_report(reports[name], evidence.get("configDigest"), now=now)
        require(language in summary["types"], name + " language inventory missing")
        images[name] = summary
        if any(summary["severityCounts"][s] for s in ("HIGH", "CRITICAL", "UNKNOWN")):
            blockers.append(name + ": unresolved HIGH/CRITICAL/UNKNOWN findings")
        uncovered = [p for p in summary["unversionedPackages"] if not (
            name == "proxy" and summary["versionedCaddyModule"] and p["Name"] == "caddy"
            and p["Type"] == "gobinary" and p["purl"] == "pkg:golang/caddy")]
        summary["coverageNotes"] = []
        if len(uncovered) != len(summary["unversionedPackages"]):
            summary["coverageNotes"].append("Unversioned caddy build wrapper; published github.com/caddyserver/caddy/v2 module is inventoried at " + summary["versionedCaddyModule"])
        if uncovered:
            blockers.append(name + ": unversioned packages need independent version/advisory evidence")
    version = manifest["images"]["app"].get("javaRuntimeVersion", "")
    match = re.fullmatch(r"21\.0\.(\d+)(?:\.(\d+))?\+(\d+)", version)
    require(match is not None, "Temurin 21 runtime evidence missing")
    if tuple(int(v or 0) for v in match.groups()) < (13, 0, 9):
        blockers.append("JRE: update to at least Temurin 21.0.13+9 and re-audit")
    return {"checkedAt": now.isoformat(), "containerGatePassed": not blockers,
            "publicReleaseReady": False, "blockers": blockers, "images": images,
            "limitations": ["Version matching is not exploit reachability analysis.",
                            "No finding suppressions or risk acceptances are applied.",
                            "Passing this gate alone does not approve deployment, privacy recovery or M4."]}


def evaluate_directory(directory, *, now):
    manifest = json.loads((directory / "audit-manifest.json").read_text(encoding="utf-8"))
    reports = {}
    for name in SERVICES:
        raw = (directory / (name + "-trivy.json")).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == manifest["images"][name].get("reportSha256"), "Report hash mismatch")
        reports[name] = json.loads(raw)
    return evaluate_evidence(manifest, reports, now=now)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = evaluate_directory(args.evidence, now=datetime.now(timezone.utc))
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        result = {"checkedAt": datetime.now(timezone.utc).isoformat(), "containerGatePassed": False,
                  "publicReleaseReady": False, "evidenceComplete": False,
                  "blockers": ["Incomplete container evidence: " + type(error).__name__]}
        (args.evidence / "release-readiness.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("INCOMPLETE " + result["blockers"][0])
        return 2
    result["evidenceComplete"] = True
    (args.evidence / "release-readiness.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for name, summary in result["images"].items():
        print(name + ": " + json.dumps(summary["severityCounts"], sort_keys=True))
    for reason in result["blockers"]:
        print("BLOCKED " + reason)
    return 0 if result["containerGatePassed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        sys.exit("Container evidence check incomplete: " + type(error).__name__)
