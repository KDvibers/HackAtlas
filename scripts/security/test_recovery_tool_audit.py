import unittest

from audit_recovery_tool import evaluate


def fixture():
    return {'Results': [{'Type': 'gobinary', 'Packages': [
        {'Name': 'github.com/restic/restic', 'Version': 'v0.19.1'},
        {'Name': 'stdlib', 'Version': 'v1.27.4'}], 'Vulnerabilities': []}]}


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

    def test_versioned_inventory_still_requires_the_pinned_restic_and_go(self):
        for package, version in ((0, 'v0.19.0'), (1, 'v1.27.3')):
            with self.subTest(package=package, version=version):
                report = fixture()
                report['Results'][0]['Packages'][package]['Version'] = version
                result = evaluate(report)
                self.assertTrue(result['inventoryComplete'])
                self.assertFalse(result['versionsMatchPin'])
                self.assertFalse(result['dependencyGatePassed'])
        result = evaluate(fixture())
        self.assertTrue(result['versionsMatchPin'])
        self.assertEqual('v0.19.1', result['resticModuleVersion'])
        self.assertEqual('v1.27.4', result['goVersion'])

    def test_duplicate_pinned_module_cannot_hide_a_version_mismatch(self):
        report = fixture()
        report['Results'][0]['Packages'].append({'Name': 'stdlib', 'Version': 'v1.27.3'})
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            evaluate(report)

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
