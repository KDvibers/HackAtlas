import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_maven_dependencies as scanner


class DependencyCheckTest(unittest.TestCase):
    def test_inventory_includes_transitive_runtime_and_classifier_without_test_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dependencies.txt"
            path.write_text("The following files have been resolved:\n"
                            "g:core:jar:1.0:compile -- module core [auto]\n"
                            "g:native:jar:linux:2.0:runtime\ng:tests:jar:1.0:test\n", encoding="utf-8")
            self.assertEqual(scanner.dependencies(path), [("g:core", "1.0"), ("g:native", "2.0")])
            path.write_text("No dependencies were resolved.\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                scanner.dependencies(path)

    def test_findings_on_later_pages_are_not_lost(self):
        with patch.object(scanner, "query_batch", side_effect=[
            [{"next_page_token": "page2"}], [{"vulns": [{"id": "TEST-ADVISORY"}]}],
        ]) as query:
            self.assertEqual(scanner.audit([("g:core", "1.0")]), [
                {"package": "g:core", "version": "1.0", "vulnerabilities": ["TEST-ADVISORY"]}])
            self.assertEqual(query.call_args_list[1].args[0][0]["page_token"], "page2")

    def test_repeating_pagination_token_fails_instead_of_returning_partial_success(self):
        with patch.object(scanner, "query_batch", return_value=[{"next_page_token": "repeated"}]):
            with self.assertRaises(ValueError):
                scanner.audit([("g:core", "1.0")])

    def test_incomplete_batch_fails_closed(self):
        response = io.BytesIO(json.dumps({"results": []}).encode())
        with patch.object(scanner.urllib.request, "urlopen", return_value=response):
            with self.assertRaises(ValueError):
                scanner.query_batch([{"package": {"ecosystem": "Maven", "name": "g:core"}, "version": "1"}])

    def test_service_error_does_not_become_clean_report(self):
        with patch.object(scanner, "query_batch", side_effect=OSError("service unavailable")):
            with self.assertRaises(OSError):
                scanner.audit([("g:core", "1.0")])
        with patch.object(scanner, "query_batch", return_value=[{"error": "service unavailable"}]):
            with self.assertRaises(ValueError):
                scanner.audit([("g:core", "1.0")])


if __name__ == "__main__":
    unittest.main()
