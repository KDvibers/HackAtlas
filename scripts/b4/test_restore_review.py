"""Review validation tests; PostgreSQL effects are verified by run_rehearsal.py."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra/release/ops"))
from restore_review import read_private_review, target_database, validate_review

NOW = datetime(2026, 10, 26, 18, 0, tzinfo=timezone.utc)
USER_ID = "11111111-2222-4333-8444-555555555555"


def fixtures():
    state = {"kind": "hackatlas.restore", "formatVersion": 1, "project": "hackatlas-b4-owned",
             "database": "hackatlas_restore_owned", "backupSha256": "a" * 64,
             "restoreId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "state": "QUARANTINED",
             "restoredAt": (NOW - timedelta(minutes=5)).isoformat()}
    review = {key: state[key] for key in ("formatVersion", "project", "database", "backupSha256", "restoreId")}
    review.update({"sourceWritesStopped": True, "deletionsReviewed": True,
                   "reviewedThrough": NOW.isoformat(), "deletedAccountIds": [USER_ID]})
    return state, review


class RestoreReviewTests(unittest.TestCase):
    def test_valid_review_returns_only_confirmed_canonical_ids(self):
        state, review = fixtures()
        self.assertEqual([USER_ID], validate_review(review, state, now=NOW))

    def test_empty_list_requires_explicit_completed_review(self):
        state, review = fixtures()
        review["deletedAccountIds"] = []
        self.assertEqual([], validate_review(review, state, now=NOW))
        review["deletionsReviewed"] = False
        with self.assertRaises(ValueError):
            validate_review(review, state, now=NOW)

    def test_unconfirmed_or_truthy_string_flags_are_rejected(self):
        for key in ("sourceWritesStopped", "deletionsReviewed"):
            for value in (False, "true", 1, None):
                state, review = fixtures()
                review[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_review(review, state, now=NOW)

    def test_review_is_bound_to_project_database_archive_and_attempt(self):
        for key, value in (("project", "another"), ("database", "hackatlas_restore_other"),
                           ("backupSha256", "b" * 64), ("restoreId", USER_ID)):
            state, review = fixtures()
            review[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_review(review, state, now=NOW)

    def test_unknown_missing_and_boolean_version_are_rejected(self):
        for mutation in (lambda r: r.update({"unexpected": True}), lambda r: r.pop("deletedAccountIds"),
                         lambda r: r.update({"formatVersion": True})):
            state, review = fixtures()
            mutation(review)
            with self.assertRaises(ValueError):
                validate_review(review, state, now=NOW)

    def test_uuid_duplicates_and_sql_like_values_are_rejected(self):
        for ids in ([USER_ID, USER_ID.upper()], ["'); GRANT ALL --"], [12], None, "all"):
            state, review = fixtures()
            review["deletedAccountIds"] = ids
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                validate_review(review, state, now=NOW)

    def test_source_database_can_never_be_an_approval_target(self):
        for value in ("hackatlas", "postgres", "hackatlas_restore_", 'hackatlas_restore_x";'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                target_database(value)

    def test_review_timestamp_must_cover_restore_and_be_recent_aware_not_future(self):
        values = ("bad", "2026-10-26T18:00:00", (NOW - timedelta(minutes=6)).isoformat(),
                  (NOW + timedelta(minutes=6)).isoformat())
        for value in values:
            state, review = fixtures()
            review["reviewedThrough"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_review(review, state, now=NOW)
        state, review = fixtures()
        state["restoredAt"] = (NOW - timedelta(days=3)).isoformat()
        review["reviewedThrough"] = (NOW - timedelta(hours=25)).isoformat()
        with self.assertRaises(ValueError):
            validate_review(review, state, now=NOW)

    def test_approved_or_changed_state_is_not_reusable(self):
        for change in ({"state": "APPROVED_FOR_SMOKE"}, {"kind": "unrelated"}, {"unexpected": "value"}):
            state, review = fixtures()
            state.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_review(review, state, now=NOW)

    def test_private_file_roundtrip_and_world_readable_file_refusal(self):
        _, review = fixtures()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "privacy.restore-review.json"
            path.write_text(json.dumps(review), encoding="utf-8")
            os.chmod(path, 0o600)
            self.assertEqual(review, read_private_review(path))
            os.chmod(path, 0o644)
            with self.assertRaises(ValueError):
                read_private_review(path)

    def test_symlink_and_duplicate_json_keys_are_refused(self):
        _, review = fixtures()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "review"
            path.write_text(json.dumps(review)[:-1] + ',"deletionsReviewed":true}', encoding="utf-8")
            os.chmod(path, 0o600)
            with self.assertRaises(ValueError):
                read_private_review(path)
            link = Path(temporary) / "symlink"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                read_private_review(link)

    def test_named_pipe_is_refused_without_waiting_for_a_writer(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "pipe"
            os.mkfifo(path)
            ops = Path(__file__).resolve().parents[2] / "infra/release/ops"
            code = "import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); from restore_review import read_private_review; read_private_review(Path(sys.argv[2]))"
            process = subprocess.Popen([sys.executable, "-c", code, str(ops), str(path)],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                _, error = process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                self.fail("A non-regular review file must be refused without blocking")
            self.assertNotEqual(0, process.returncode)
            self.assertIn(b"private regular file", error)


if __name__ == "__main__":
    unittest.main()
