import copy
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import sys
from unittest.mock import patch

from container_policy import (PolicyError, SCANNER_IMAGE, SCANNER_VERSION,
                              evaluate_directory, evaluate_evidence, evaluate_report, main)
from run_container_audit import cleanup_cache, config_digest, run

NOW = datetime(2026, 10, 26, 0, 0, tzinfo=timezone.utc)
CONFIG = "sha256:" + "a" * 64


def evidence():
    manifest = {"formatVersion": 1, "createdAt": NOW.isoformat(), "scannerImage": SCANNER_IMAGE,
                "database": {"Version": 2, "UpdatedAt": NOW.isoformat()},
                "javaDatabase": {"Version": 1, "UpdatedAt": NOW.isoformat()},
                "images": {}}
    reports = {}
    for name, kind in (("app", "jar"), ("proxy", "gobinary"), ("postgres", "gobinary")):
        manifest["images"][name] = {"configDigest": CONFIG, "inspectedImageId": "sha256:" + "b" * 64,
                                    "archiveSha256": "c" * 64, "javaRuntimeVersion": "21.0.13+9"}
        reports[name] = {"SchemaVersion": 2, "ArtifactType": "container_image", "Trivy": {"Version": SCANNER_VERSION},
                         "CreatedAt": NOW.isoformat(), "Metadata": {"ImageID": CONFIG}, "Results": [
                             {"Class": "os-pkgs", "Type": "debian", "Packages": [{"Name": "libc", "Version": "1"}]},
                             {"Class": "lang-pkgs", "Type": kind, "Packages": [{"Name": kind, "Version": "1"}]}]}
    return manifest, reports


def no_go_fixture(directory, *, change=lambda manifest, proof, reports: None):
    manifest, reports = evidence()
    reports["postgres"]["Results"].pop()
    for name, report in reports.items():
        raw = json.dumps(report).encode()
        manifest["images"][name]["reportSha256"] = hashlib.sha256(raw).hexdigest()
    postgres = manifest["images"]["postgres"]
    postgres["rehearsedContainerImageId"] = postgres["inspectedImageId"]
    files = {"usr/bin/postgres": ["elf", 0o755, 0, 0, "d" * 64]}
    proof = {
        "formatVersion": 1,
        "method": "postgres-bookworm-rootfs-diff-v1",
        "baseReference": "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5",
        "baseImageId": "sha256:" + "e" * 64,
        "baseLayers": ["sha256:" + "0" * 64],
        "candidateLayers": ["sha256:" + "0" * 64, "sha256:" + "1" * 64],
        "candidateUser": "999:999",
        "candidate": {key: postgres[key] for key in (
            "inspectedImageId", "rehearsedContainerImageId", "configDigest", "archiveSha256", "reportSha256")},
        "baseFiles": {**copy.deepcopy(files), "usr/local/bin/gosu": ["go-elf", 0o755, 0, 0, "f" * 64]},
        "candidateFiles": copy.deepcopy(files),
    }
    change(manifest, proof, reports)
    for name, report in reports.items():
        raw = json.dumps(report).encode()
        (directory / (name + "-trivy.json")).write_bytes(raw)
        manifest["images"][name]["reportSha256"] = hashlib.sha256(raw).hexdigest()
    proof_raw = json.dumps(proof).encode()
    (directory / "postgres-no-go.json").write_bytes(proof_raw)
    postgres["noGoProofSha256"] = hashlib.sha256(proof_raw).hexdigest()
    (directory / "audit-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


class ContainerPolicyTest(unittest.TestCase):
    def test_failed_command_preserves_diagnostic_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "tool.log"
            with self.assertRaises(RuntimeError):
                run([sys.executable, "-c", "import sys; print('diagnostic', file=sys.stderr); sys.exit(3)"], output=log)
            self.assertIn("diagnostic", log.with_suffix(".log.log").read_text())

    def test_scanner_cleanup_refuses_foreign_container(self):
        with patch("run_container_audit.run", side_effect=[b"abc\n", b'[{"Config":{"Labels":{}}}]']) as command:
            with self.assertRaises(ValueError):
                cleanup_cache("owned")
            self.assertFalse(any(call.args[0][:2] == ["docker", "rm"] for call in command.call_args_list))

    def test_scanner_cleanup_refuses_foreign_volume(self):
        with patch("run_container_audit.run", side_effect=[b"", b'[{"Labels":{"hackatlas.audit":"other"}}]']) as command:
            with self.assertRaises(ValueError):
                cleanup_cache("owned")
            self.assertFalse(any(call.args[0][:3] == ["docker", "volume", "rm"] for call in command.call_args_list))

    def test_complete_evidence_binds_config_not_oci_index(self):
        manifest, reports = evidence()
        result = evaluate_evidence(manifest, reports, now=NOW)
        self.assertTrue(result["containerGatePassed"])
        self.assertFalse(result["publicReleaseReady"])

    def test_high_critical_unknown_remain_blocking_without_a_fixed_version(self):
        for severity in ("HIGH", "CRITICAL", "UNKNOWN"):
            with self.subTest(severity=severity):
                manifest, reports = evidence()
                reports["proxy"]["Results"][0]["Vulnerabilities"] = [{"VulnerabilityID": "CVE-test", "PkgName": "p",
                    "InstalledVersion": "1", "Severity": severity, "Status": "will_not_fix"}]
                result = evaluate_evidence(manifest, reports, now=NOW)
                self.assertFalse(result["containerGatePassed"])
                self.assertEqual(1, result["images"]["proxy"]["severityCounts"][severity])
                self.assertEqual("will_not_fix", result["images"]["proxy"]["findings"][0]["Status"])

    def test_older_jre_is_blocked_separately_from_jar_scan(self):
        manifest, reports = evidence()
        manifest["images"]["app"]["javaRuntimeVersion"] = "21.0.12.1+1"
        result = evaluate_evidence(manifest, reports, now=NOW)
        self.assertFalse(result["containerGatePassed"])
        self.assertIn("JRE:", result["blockers"][0])

    def test_unversioned_binary_module_is_visible_and_blocks_release(self):
        manifest, reports = evidence()
        reports["proxy"]["Results"][1]["Packages"].append({"Name": "caddy"})
        result = evaluate_evidence(manifest, reports, now=NOW)
        self.assertFalse(result["containerGatePassed"])
        self.assertEqual("caddy", result["images"]["proxy"]["unversionedPackages"][0]["Name"])

    def test_build_wrapper_is_covered_only_by_exact_versioned_published_module(self):
        manifest, reports = evidence()
        packages = reports["proxy"]["Results"][1]["Packages"]
        packages.extend([{"Name": "caddy", "Identifier": {"PURL": "pkg:golang/caddy"}},
                         {"Name": "github.com/caddyserver/caddy/v2", "Version": "v2.11.7"}])
        result = evaluate_evidence(manifest, reports, now=NOW)
        self.assertTrue(result["containerGatePassed"])
        self.assertEqual("v2.11.7", result["images"]["proxy"]["versionedCaddyModule"])
        self.assertTrue(result["images"]["proxy"]["coverageNotes"])
        packages[-1]["Name"] += "-other"
        self.assertFalse(evaluate_evidence(manifest, reports, now=NOW)["containerGatePassed"])
        packages[-1]["Name"] = "github.com/caddyserver/caddy/v2"
        packages[-1]["Version"] = "unknown"
        self.assertFalse(evaluate_evidence(manifest, reports, now=NOW)["containerGatePassed"])

    def test_missing_service_or_language_inventory_is_incomplete(self):
        manifest, reports = evidence()
        del reports["postgres"]
        with self.assertRaises(PolicyError):
            evaluate_evidence(manifest, reports, now=NOW)
        for service in ("app", "proxy", "postgres"):
            with self.subTest(service=service):
                manifest, reports = evidence()
                reports[service]["Results"][1]["Packages"] = []
                with self.assertRaisesRegex(PolicyError, service + " language inventory missing"):
                    evaluate_evidence(manifest, reports, now=NOW)

    def test_postgres_no_go_proof_allows_only_the_missing_language_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            no_go_fixture(directory)
            result = evaluate_directory(directory, now=NOW)
            self.assertTrue(result["containerGatePassed"])
            self.assertFalse(result["publicReleaseReady"])
            self.assertTrue(result["images"]["postgres"]["coverageNotes"])

    def test_postgres_no_go_proof_does_not_hide_os_findings(self):
        def add_finding(manifest, proof, reports):
            reports["postgres"]["Results"][0]["Vulnerabilities"] = [
                {"VulnerabilityID": "CVE-test", "PkgName": "libc", "InstalledVersion": "1", "Severity": "CRITICAL"}]
            proof["candidate"]["reportSha256"] = hashlib.sha256(json.dumps(reports["postgres"]).encode()).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            no_go_fixture(directory, change=add_finding)
            result = evaluate_directory(directory, now=NOW)
            self.assertFalse(result["containerGatePassed"])
            self.assertEqual(1, result["images"]["postgres"]["severityCounts"]["CRITICAL"])

    def test_missing_or_modified_postgres_proof_is_incomplete(self):
        for change in (lambda file: file.unlink(), lambda file: file.write_bytes(file.read_bytes() + b" ")):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                no_go_fixture(directory)
                change(directory / "postgres-no-go.json")
                with self.assertRaises(PolicyError):
                    evaluate_directory(directory, now=NOW)

    def test_postgres_proof_bound_to_exact_image_and_scan(self):
        for field in ("inspectedImageId", "rehearsedContainerImageId", "configDigest", "archiveSha256", "reportSha256"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                other = ("sha256:" if field.endswith(("Id", "Digest")) else "") + "0" * 64
                no_go_fixture(directory, change=lambda m, p, r: p["candidate"].update({field: other}))
                with self.assertRaises(PolicyError):
                    evaluate_directory(directory, now=NOW)

    def test_proof_cannot_allow_altered_files_or_a_different_base(self):
        changes = (
            lambda m, p, r: p.update(baseReference="postgres:latest"),
            lambda m, p, r: p.update(candidateUser="0:0"),
            lambda m, p, r: p["candidateLayers"].reverse(),
            lambda m, p, r: p["candidateFiles"]["usr/bin/postgres"].__setitem__(4, "0" * 64),
            lambda m, p, r: p["candidateFiles"].update({"usr/bin/extra": ["elf", 0o755, 0, 0, "0" * 64]}),
            lambda m, p, r: p["candidateFiles"].update({"usr/local/bin/gosu": p["baseFiles"]["usr/local/bin/gosu"]}),
            lambda m, p, r: p["candidateFiles"].clear(),
        )
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                no_go_fixture(directory, change=change)
                with self.assertRaises(PolicyError):
                    evaluate_directory(directory, now=NOW)

    def test_postgres_proof_cannot_waive_proxy_language_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            no_go_fixture(directory, change=lambda m, p, r: r["proxy"]["Results"].pop())
            with self.assertRaisesRegex(PolicyError, "proxy language inventory missing"):
                evaluate_directory(directory, now=NOW)

    def test_trivy_go_result_contradicts_the_postgres_proof(self):
        def add_go_result(manifest, proof, reports):
            report = reports["postgres"]
            report["Results"].append({"Class": "lang-pkgs", "Type": "gobinary", "Packages": []})
            proof["candidate"]["reportSha256"] = hashlib.sha256(json.dumps(report).encode()).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            no_go_fixture(directory, change=add_go_result)
            with self.assertRaisesRegex(PolicyError, "contradicts"):
                evaluate_directory(directory, now=NOW)

    def test_stale_missing_future_or_timezone_free_database_is_incomplete(self):
        for value in (None, "invalid", "2026-10-26T00:00:00", (NOW + timedelta(hours=1)).isoformat(),
                      (NOW - timedelta(days=3)).isoformat()):
            with self.subTest(value=value):
                manifest, reports = evidence()
                manifest["database"]["UpdatedAt"] = value
                with self.assertRaises(PolicyError):
                    evaluate_evidence(manifest, reports, now=NOW)

    def test_missing_java_database_and_old_report_are_incomplete(self):
        manifest, reports = evidence()
        del manifest["javaDatabase"]
        with self.assertRaises(PolicyError):
            evaluate_evidence(manifest, reports, now=NOW)
        manifest, reports = evidence()
        reports["app"]["CreatedAt"] = (NOW - timedelta(days=2)).isoformat()
        with self.assertRaises(PolicyError):
            evaluate_evidence(manifest, reports, now=NOW)

    def test_wrong_config_digest_and_scanner_are_rejected(self):
        _, reports = evidence()
        report = reports["app"]
        for key, value in (("Metadata", {"ImageID": "sha256:" + "d" * 64}), ("Trivy", {"Version": "unknown"})):
            bad = copy.deepcopy(report)
            bad[key] = value
            with self.assertRaises(PolicyError):
                evaluate_report(bad, CONFIG, now=NOW)

    def test_empty_or_malformed_results_do_not_mean_zero_vulnerabilities(self):
        _, reports = evidence()
        for results in ([], [None], [{"Class": "os-pkgs", "Packages": []}],
                        [{"Class": "os-pkgs", "Packages": [None]}]):
            with self.subTest(results=results):
                bad = copy.deepcopy(reports["app"])
                bad["Results"] = results
                with self.assertRaises(PolicyError):
                    evaluate_report(bad, CONFIG, now=NOW)

    def test_unidentified_finding_and_scanner_error_are_incomplete(self):
        for change in ({"Vulnerabilities": [{"Severity": "HIGH"}]}, {"Errors": ["failed layer"]}):
            _, reports = evidence()
            reports["app"]["Results"][0].update(change)
            with self.assertRaises(PolicyError):
                evaluate_report(reports["app"], CONFIG, now=NOW)

    def test_changed_raw_report_is_rejected(self):
        manifest, reports = evidence()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, report in reports.items():
                raw = json.dumps(report).encode()
                (directory / (name + "-trivy.json")).write_bytes(raw)
                manifest["images"][name]["reportSha256"] = hashlib.sha256(raw).hexdigest()
            (directory / "audit-manifest.json").write_text(json.dumps(manifest))
            self.assertTrue(evaluate_directory(directory, now=NOW)["containerGatePassed"])
            with (directory / "app-trivy.json").open("a") as changed:
                changed.write(" ")
            with self.assertRaises(PolicyError):
                evaluate_directory(directory, now=NOW)

    def test_incomplete_recheck_replaces_previous_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "release-readiness.json"
            output.write_text('{"containerGatePassed":true}', encoding="utf-8")
            with patch("sys.argv", ["policy", "--evidence", temporary]), patch("sys.stdout", io.StringIO()):
                self.assertEqual(2, main())
            result = json.loads(output.read_text())
            self.assertFalse(result["containerGatePassed"])
            self.assertFalse(result["evidenceComplete"])

    def test_archive_config_is_hashed_without_extracting_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "image.tar"
            raw = b'{"architecture":"amd64"}'
            entries = {"manifest.json": json.dumps([{"Config": "blobs/sha256/config"}]).encode(),
                       "blobs/sha256/config": raw}
            with tarfile.open(path, "w") as archive:
                for name, content in entries.items():
                    member = tarfile.TarInfo(name)
                    member.size = len(content)
                    archive.addfile(member, io.BytesIO(content))
            self.assertEqual("sha256:" + hashlib.sha256(raw).hexdigest(), config_digest(path))


if __name__ == "__main__":
    unittest.main()
