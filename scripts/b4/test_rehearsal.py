"""Safety-boundary tests; these tests never start Docker or touch an existing stack."""

import unittest

from run_rehearsal import Rehearsal, RehearsalFailure, fingerprint, sql_value, validate_bindings, validate_owned


class RehearsalSafetyTests(unittest.TestCase):
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

    def test_failure_log_redaction_handles_overlapping_credentials(self):
        rehearsal = object.__new__(Rehearsal)
        rehearsal.redactions = ["secret", "secret-long"]
        self.assertEqual(rehearsal.redact("secret-long / secret"), "[REDACTED] / [REDACTED]")


if __name__ == "__main__":
    unittest.main()
