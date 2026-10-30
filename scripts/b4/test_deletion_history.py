"""Deletion export validation and private-file behaviour; SQL covered by rehearsal."""

from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra/release/ops"))
from deletion_history import make_review, validate_export, write_private_json
from restore_review import read_private_review, validate_review

NOW = datetime(2026, 10, 30, 18, 30, tzinfo=timezone.utc)
USER = "11111111-2222-4333-8444-555555555555"


def fixture():
    return {"formatVersion": 1, "sourceProject": "hackatlas-owned", "sourceDatabase": "hackatlas",
            "exportedAt": NOW.isoformat(), "accountCount": 1, "deletedAccountIds": [USER],
            "historyComplete": False}


def state():
    return {"kind": "hackatlas.restore", "formatVersion": 1, "project": "hackatlas-owned",
            "database": "hackatlas_restore_owned", "backupSha256": "a" * 64,
            "restoreId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "state": "QUARANTINED",
            "restoredAt": (NOW - timedelta(minutes=5)).isoformat()}


def check(value):
    return validate_export(value, "hackatlas-owned", "hackatlas", now=NOW)


class DeletionHistoryTests(unittest.TestCase):
    def test_valid_export_keeps_history_incomplete(self):
        self.assertEqual(fixture(), check(fixture()))

    def test_empty_export_never_confirms_no_deletions(self):
        value = fixture()
        value.update(deletedAccountIds=[], accountCount=0)
        review = make_review(check(value), state())
        self.assertFalse(review["deletionsReviewed"])
        with self.assertRaises(ValueError):
            validate_review(review, state(), now=NOW)

    def test_unknown_missing_and_wrong_types_are_rejected(self):
        mutations = [lambda r: r.update(extra=True), lambda r: r.pop("accountCount"),
                     lambda r: r.update(formatVersion=True), lambda r: r.update(accountCount=True),
                     lambda r: r.update(historyComplete=True), lambda r: r.update(historyComplete=0),
                     lambda r: r.update(accountCount=2), lambda r: r.update(deletedAccountIds=None)]
        for mutation in mutations:
            value = fixture()
            mutation(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                check(value)

    def test_source_identity_must_match_explicit_operator_arguments(self):
        for key, wrong in [("sourceProject", "another"), ("sourceDatabase", "hackatlas_restore_old")]:
            value = fixture()
            value[key] = wrong
            with self.subTest(key=key), self.assertRaises(ValueError):
                check(value)
        with self.assertRaises(ValueError):
            validate_export(fixture(), "not a project", "hackatlas", now=NOW)

    def test_bad_duplicate_unsorted_or_too_many_ids_are_rejected(self):
        for ids in [[USER, USER], ["'; DELETE FROM users --"], [12],
                    ["ffffffff-ffff-4fff-8fff-ffffffffffff", USER], [USER] * 10001]:
            value = fixture()
            value.update(deletedAccountIds=ids, accountCount=len(ids))
            with self.subTest(size=len(ids)), self.assertRaises(ValueError):
                check(value)

    def test_export_time_is_aware_and_not_in_the_future(self):
        for wrong in ["not-a-date", "2026-10-30T18:30:00", (NOW + timedelta(minutes=6)).isoformat()]:
            value = fixture()
            value["exportedAt"] = wrong
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                check(value)
        value = fixture()
        value["exportedAt"] = (NOW - timedelta(days=90)).isoformat()
        self.assertFalse(check(value)["historyComplete"])

    def test_draft_has_exact_b42_keys_false_flags_and_no_review_time(self):
        target = state()
        export = check(fixture())
        draft = make_review(export, target)
        expected = {"formatVersion", "project", "database", "backupSha256", "restoreId",
                    "sourceWritesStopped", "deletionsReviewed", "reviewedThrough", "deletedAccountIds"}
        self.assertEqual(expected, set(draft))
        self.assertEqual([USER], draft["deletedAccountIds"])
        self.assertIs(draft["sourceWritesStopped"], False)
        self.assertIs(draft["deletionsReviewed"], False)
        self.assertIsNone(draft["reviewedThrough"])
        self.assertNotIn("sourceProject", draft)
        with self.assertRaises(ValueError):
            validate_review(draft, target, now=NOW)
        draft["deletedAccountIds"].clear()
        self.assertEqual([USER], export["deletedAccountIds"])

    def test_draft_rejects_approved_or_non_restore_target(self):
        for key, wrong in [("state", "APPROVED_FOR_SMOKE"), ("database", "hackatlas")]:
            target = state()
            target[key] = wrong
            with self.subTest(key=key), self.assertRaises(ValueError):
                make_review(check(fixture()), target)

    def test_private_file_roundtrip_and_exclusive_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.deletion-export.json"
            write_private_json(path, fixture())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(fixture(), read_private_review(path))
            original = path.read_bytes()
            with self.assertRaises((ValueError, OSError)):
                write_private_json(path, {"changed": True})
            self.assertEqual(original, path.read_bytes())

    def test_writer_refuses_symlink_fifo_and_oversize_without_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.write_text("preserved")
            link = Path(directory) / "link"
            link.symlink_to(target)
            fifo = Path(directory) / "fifo"
            os.mkfifo(fifo)
            for path in (link, fifo):
                with self.subTest(path=path.name), self.assertRaises((ValueError, OSError)):
                    write_private_json(path, fixture())
            oversized = Path(directory) / "oversized"
            with self.assertRaises(ValueError):
                write_private_json(oversized, {"huge": "a" * (1024 * 1024)})
            self.assertFalse(oversized.exists())
            self.assertEqual("preserved", target.read_text())

    def test_ten_thousand_ids_fit_private_file_without_truncation(self):
        value = fixture()
        value["deletedAccountIds"] = [str(uuid.UUID(int=index)) for index in range(10000)]
        value["accountCount"] = 10000
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "full.deletion-export.json"
            write_private_json(path, check(value))
            self.assertEqual(10000, len(read_private_review(path)["deletedAccountIds"]))
            self.assertLess(path.stat().st_size, 1024 * 1024)

    def test_output_parent_must_be_private(self):
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o755)
            path = Path(directory) / "private.json"
            try:
                with self.assertRaises(ValueError):
                    write_private_json(path, fixture())
                self.assertFalse(path.exists())
            finally:
                os.chmod(directory, 0o700)


if __name__ == "__main__":
    unittest.main()
