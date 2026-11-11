"""Safety-boundary tests; these tests never start Docker or touch an existing stack."""

import os
import shlex
import unittest
from pathlib import Path
import tempfile
from subprocess import CompletedProcess, run
from unittest.mock import patch

import run_rehearsal
from run_rehearsal import Rehearsal, RehearsalFailure, fingerprint, sql_value, validate_bindings, validate_owned


class RehearsalSafetyTests(unittest.TestCase):
    def test_previous_evidence_is_never_overwritten_or_appended(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("report.json", "commands.log"):
                path = output / name
                path.write_text("previous evidence", encoding="utf-8")
                with self.assertRaises(RehearsalFailure):
                    Rehearsal(Path("unused.jar"), output, 240)
                self.assertEqual("previous evidence", path.read_text(encoding="utf-8"))
                path.unlink()

    def test_effective_compose_accepts_only_two_loopback_proxy_ports(self):
        valid = {"services": {"app": {}, "postgres": {}, "proxy": {"ports": [
            {"host_ip": "127.0.0.1", "published": "34001"}, {"host_ip": "127.0.0.1", "published": "34002"}]}}}
        validate_bindings(valid)
        valid["services"]["proxy"]["ports"][0]["host_ip"] = "0.0.0.0"
        with self.assertRaises(RehearsalFailure):
            validate_bindings(valid)

    def test_database_port_publication_is_rejected_even_on_loopback(self):
        with self.assertRaises(RehearsalFailure):
            validate_bindings({"services": {"postgres": {"ports": [{"host_ip": "127.0.0.1"}]}}})

    def test_database_and_initializer_require_the_built_local_image(self):
        tag = "hackatlas-b4-123456789abc-postgres:local"
        image = {"image": tag, "pull_policy": "never"}
        config = {"services": {"postgres": dict(image), "init-volumes": dict(image)}}
        run_rehearsal.validate_postgres_image(config, tag)
        for service, override in (("init-volumes", {"image": "postgres:17"}),
                                  ("postgres", {"pull_policy": "always"})):
            with self.subTest(service=service, override=override):
                config["services"][service] = {**image, **override}
                with self.assertRaises(RehearsalFailure):
                    run_rehearsal.validate_postgres_image(config, tag)
                config["services"][service] = dict(image)

    @unittest.skipUnless(os.name == "posix", "Release volume setup uses a Linux shell")
    def test_existing_unmarked_database_volume_is_refused_before_chmod(self):
        compose = run_rehearsal.yaml.safe_load((run_rehearsal.RELEASE / "compose.yaml").read_text(encoding="utf-8"))
        init = compose["services"]["init-volumes"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            command = init["command"][0].replace("$$", "$")
            for name in ("postgres-data", "caddy-data", "caddy-config"):
                (root / name).mkdir(mode=0o755)
                command = command.replace("/" + name, shlex.quote(str(root / name)))
            data = root / "postgres-data"
            (data / "PG_VERSION").write_text("17\n", encoding="ascii")
            mode = data.stat().st_mode
            result = run([*init["entrypoint"], command], capture_output=True, text=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("separate Alpine volume", result.stderr)
            self.assertEqual(mode, data.stat().st_mode)

    def test_cleanup_requires_project_service_and_release_ownership(self):
        item = {"Config": {"Labels": {"com.docker.compose.project": "owned",
                                    "com.docker.compose.service": "app", "hackatlas.release": "true"}}}
        validate_owned([item], "owned")
        with self.assertRaises(RehearsalFailure):
            validate_owned([item], "another-project")
        item["Config"]["Labels"]["com.docker.compose.service"] = "unrelated"
        with self.assertRaises(RehearsalFailure):
            validate_owned([item], "owned")

    def test_unlabelled_container_is_never_adopted_for_cleanup(self):
        with self.assertRaises(RehearsalFailure):
            validate_owned([{"Config": {}}], "owned")

    def test_fingerprint_ignores_row_order_but_detects_mutation_and_duplicates(self):
        self.assertEqual(fingerprint(["a", "b"]), fingerprint(["b", "a"]))
        self.assertNotEqual(fingerprint(["a", "b"]), fingerprint(["a", "c"]))
        self.assertNotEqual(fingerprint(["a"]), fingerprint(["a", "a"]))

    def test_synthetic_sql_values_cannot_escape_the_string_literal(self):
        self.assertEqual(sql_value("x'); DROP TABLE users; --"), "'x''); DROP TABLE users; --'")
        self.assertEqual(sql_value(None), "NULL")
        self.assertEqual(sql_value(True), "true")

    def test_failed_build_records_both_redacted_streams(self):
        rehearsal = object.__new__(Rehearsal)
        rehearsal.env = {}
        rehearsal.redactions = ["disposable-secret"]
        failure = CompletedProcess(["docker"], 1, "APT failed resolving disposable-secret", "exit 100")
        with tempfile.TemporaryDirectory() as temporary:
            rehearsal.output = Path(temporary)
            with patch("run_rehearsal.subprocess.run", return_value=failure):
                with self.assertRaises(RehearsalFailure):
                    rehearsal.run("Build application", ["docker"], log_stdout_on_failure=True)
            logged = (rehearsal.output / "commands.log").read_text(encoding="utf-8")
            self.assertIn("APT failed resolving [REDACTED]", logged)
            self.assertIn("exit 100", logged)
            self.assertNotIn("disposable-secret", logged)

    def test_other_failed_commands_keep_stdout_out_of_the_artifact(self):
        rehearsal = object.__new__(Rehearsal)
        rehearsal.env = {}
        rehearsal.redactions = []
        failure = CompletedProcess(["docker"], 1, "private query output", "command failed")
        with tempfile.TemporaryDirectory() as temporary:
            rehearsal.output = Path(temporary)
            with patch("run_rehearsal.subprocess.run", return_value=failure):
                rehearsal.run("Run query", ["docker"], allow_failure=True)
            logged = (rehearsal.output / "commands.log").read_text(encoding="utf-8")
            self.assertNotIn("private query output", logged)
            self.assertIn("command failed", logged)

    def test_failure_log_redaction_handles_overlapping_credentials(self):
        rehearsal = object.__new__(Rehearsal)
        rehearsal.redactions = ["secret", "secret-long"]
        self.assertEqual(rehearsal.redact("secret-long / secret"), "[REDACTED] / [REDACTED]")


if __name__ == "__main__":
    unittest.main()
