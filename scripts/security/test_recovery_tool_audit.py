import unittest

from audit_recovery_tool import evaluate


def fixture():
    return {'Results': [{'Type': 'gobinary', 'Packages': [
        {'Name': 'github.com/restic/restic', 'Version': 'v0.19.1'},
        {'Name': 'stdlib', 'Version': 'v1.26.4'}], 'Vulnerabilities': []}]}


class RecoveryToolAuditTests(unittest.TestCase):
    def test_inventory_and_no_blocking_matches_are_required(self):
        result = evaluate(fixture())
        self.assertTrue(result['inventoryComplete'])
        self.assertTrue(result['dependencyGatePassed'])
        self.assertFalse(result['publicReleaseReady'])

    def test_high_critical_unknown_and_unexpected_severity_block(self):
        for severity in ['HIGH', 'CRITICAL', 'UNKNOWN', 'unrecognized']:
            report = fixture()
            report['Results'][0]['Vulnerabilities'] = [{'Severity': severity}]
            with self.subTest(severity=severity):
                self.assertFalse(evaluate(report)['dependencyGatePassed'])

    def test_unversioned_main_module_is_not_silently_waived(self):
        report = fixture()
        report['Results'][0]['Packages'][0].pop('Version')
        result = evaluate(report)
        self.assertFalse(result['inventoryComplete'])
        self.assertFalse(result['dependencyGatePassed'])

    def test_empty_wrong_type_and_missing_stdlib_refused(self):
        for report in [{}, {'Results': []}, {'Results': [{'Type': 'jar', 'Packages': []}]}]:
            with self.subTest(report=report), self.assertRaises(ValueError):
                evaluate(report)
        report = fixture()
        report['Results'][0]['Packages'].pop()
        with self.assertRaises(ValueError):
            evaluate(report)

    def test_malformed_vulnerability_field_is_incomplete_evidence(self):
        report = fixture()
        report['Results'][0]['Vulnerabilities'] = 'not a list'
        with self.assertRaises(ValueError):
            evaluate(report)


if __name__ == '__main__':
    unittest.main()
