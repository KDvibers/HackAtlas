"""Regression checks for smoke reporting; never invokes adb or the backend."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import android_remote_smoke as smoke


class SmokeReportingTest(unittest.TestCase):
    def test_interruption_cannot_leave_a_pass_report(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = SimpleNamespace(out=Path(directory), base="http://10.0.2.2:8080/api/v1/",
                results=[], http=[], run=Mock(side_effect=KeyboardInterrupt()),
                capture=Mock(), restore=Mock(return_value=[]))
            argv = ["smoke", "--api-base-url", "http://127.0.0.1:8080/api/v1",
                    "--event-id", "39287368-5259-5235-a1c4-25b4e1e8421e"]
            with patch.object(smoke, "Smoke", return_value=runner), patch("sys.argv", argv):
                with self.assertRaises(BaseException):
                    smoke.main()
            report = json.loads((runner.out / "remote-smoke.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", report["status"])
            self.assertIn("KeyboardInterrupt", report["failure"])
            runner.restore.assert_called_once()

    def test_empty_assertion_cannot_report_success_and_still_restores(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = SimpleNamespace(out=Path(directory), base="http://10.0.2.2:8080/api/v1/",
                results=[], http=[], run=Mock(side_effect=AssertionError()),
                capture=Mock(), restore=Mock(return_value=[]))
            argv = ["smoke", "--api-base-url", "http://127.0.0.1:8080/api/v1",
                    "--event-id", "39287368-5259-5235-a1c4-25b4e1e8421e"]
            with patch.object(smoke, "Smoke", return_value=runner), patch("sys.argv", argv):
                with self.assertRaises(SystemExit):
                    smoke.main()
            report = json.loads((runner.out / "remote-smoke.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", report["status"])
            self.assertIn("AssertionError", report["failure"])
            runner.restore.assert_called_once()


if __name__ == "__main__":
    unittest.main()
