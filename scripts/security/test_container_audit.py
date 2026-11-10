import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import run_container_audit
from postgres_no_go import BASE_REFERENCE
from run_container_audit import save_scan_archive


class PostgresReferenceTest(unittest.TestCase):
    project = "hackatlas-b4-123456789abc"

    def test_release_image_must_match_project_and_require_proof(self):
        tag = self.project + "-postgres:local"
        validate = run_container_audit.validate_postgres_reference
        validate(self.project, tag, with_proof=True)
        with self.assertRaises(ValueError):
            validate(self.project, tag, with_proof=False)
        with self.assertRaises(ValueError):
            validate(self.project, "hackatlas-b4-456789abcdef-postgres:local", with_proof=True)

    def test_previous_stock_and_experiment_reports_remain_auditable(self):
        validate = run_container_audit.validate_postgres_reference
        validate(self.project, BASE_REFERENCE, with_proof=False)
        validate(self.project, "postgres:17.11-bookworm-nogosu-probe", with_proof=True)
        with self.assertRaises(ValueError):
            validate(self.project, "postgres:17.11-bookworm", with_proof=False)


@unittest.skipUnless(os.name == "posix", "Scanner bind mounts are supported on Linux/WSL")
class ContainerArchiveTest(unittest.TestCase):
    def test_owner_only_docker_export_can_be_read_by_the_scanner(self):
        payload = b"docker archive payload"

        def docker_save(command):
            archive = Path(command[command.index("--output") + 1])
            descriptor = os.open(archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(payload)

        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "image.tar"
            with patch("run_container_audit.run", side_effect=docker_save):
                save_scan_archive("rehearsed-image:local", archive)

            self.assertEqual(archive.read_bytes(), payload)
            self.assertTrue(archive.stat().st_mode & stat.S_IROTH)
            self.assertFalse(archive.stat().st_mode & (stat.S_IWGRP | stat.S_IWOTH))
