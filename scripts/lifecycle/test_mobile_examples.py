"""Safety and scope checks for the actual N2 examples shared with mobile."""
import copy
import importlib
import importlib.util
import json
from pathlib import Path
import unittest

import yaml
from render_test_contract import render_test_contract, GUARDED
from validate_http import validate_responses

ROOT = Path(__file__).resolve().parents[2]


class MobileExamplesTests(unittest.TestCase):
    def test_checked_in_examples_match_candidate_and_cover_both_private_stores_and_pairs(self):
        api = render_test_contract(yaml.safe_load((ROOT / 'api/openapi.yaml').read_text(encoding='utf-8')))
        examples = json.loads((ROOT / 'docs/examples/n2-http/responses.json').read_text(encoding='utf-8'))
        self.assertEqual(31, validate_responses(api, [examples], require_coverage=False))
        self.assertEqual('0.1.1', examples['canonicalVersion'])
        self.assertEqual('0.2.0-test.1', examples['candidateVersion'])
        rows = {row['caseId']: row for row in examples['responses']}
        self.assertEqual(31, len(rows))
        expected = {'pending-register', 'pending-login', 'verified-login', 'pending-profile', 'verified-profile',
                    'verification-request-accepted', 'verification-already-verified', 'verification-confirm-success',
                    'reset-request-accepted', 'reset-verify-success', 'reset-confirm-success', 'invalid-email-confirm',
                    'invalid-reset-verify', 'invalid-reset-confirm', 'reset-required-login', 'quota-retry-after',
                    'queue-unavailable', 'revoked-me'}
        expected |= {f'pending-guard:{method}:{path}' for method, path in GUARDED}
        expected |= {f'delete-password-{mode}-{step}' for mode in ('pending', 'verified') for step in ('401', 'me-200')}
        self.assertEqual(expected, set(rows))
        guards = {(row['method'], row['operationPath']) for row in rows.values()
                  if row['caseId'].startswith('pending-guard:')}
        self.assertEqual(set(GUARDED), guards)
        self.assertEqual(2, len(examples['sequences']))
        for sequence, verified in zip(examples['sequences'], (False, True)):
            pair = [rows[key] for key in sequence['caseIds']]
            self.assertEqual(pair, self.exporter().delete_pair(pair, verified))
            self.assertTrue(sequence['sameBearer'])
        self.assertFalse(rows['pending-register']['body']['user']['emailVerified'])
        self.assertNotIn('verified-register', rows)
        for row in rows.values():
            self.assertIn('headers', row)
        def check_identity(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key == 'email':
                        self.assertRegex(item, r'^account-[0-9]+@example\.invalid$')
                    if key == 'displayName':
                        self.assertEqual('Test account', item)
                    check_identity(item)
            elif isinstance(value, list):
                for item in value:
                    check_identity(item)
        check_identity(examples)
        # Re-running the safety pass catches any accidentally added working proof/password.
        self.exporter().anonymize(examples, {})

    def exporter(self):
        self.assertIsNotNone(importlib.util.find_spec('export_mobile_examples'),
                             'The checked-in N2 example exporter is missing')
        return importlib.import_module('export_mobile_examples')

    def test_redaction_preserves_identity_links_and_rejects_actual_secrets(self):
        exporter = self.exporter()
        row = {'headers': {'X-Request-Id': '12345678-1234-4234-8234-123456789abc'},
               'body': {'accessToken': 'x' * 43, 'user': {'id': '12345678-1234-4234-8234-123456789abc',
                         'email': 'private@example.com', 'displayName': 'Private person'}}}
        clean = exporter.anonymize(row, {})
        self.assertEqual(row['body']['accessToken'], clean['body']['accessToken'])
        self.assertEqual(clean['headers']['X-Request-Id'], clean['body']['user']['id'])
        self.assertNotIn('private@example.com', json.dumps(clean))
        for key, value in (('accessToken', 'y' * 43), ('resetToken', 'S' * 43),
                           ('challengeId', 'B' * 43), ('password', 'synthetic-secret'), ('code', '12345678')):
            unsafe = copy.deepcopy(row)
            unsafe['body'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                exporter.anonymize(unsafe, {})

    def test_delete_pair_requires_contextual_401_and_matching_pending_state(self):
        exporter = self.exporter()
        rows = [{'operationPath': '/me/delete-account', 'method': 'post', 'status': 401,
                 'body': {'code': 'INVALID_CREDENTIALS'}},
                {'operationPath': '/me', 'method': 'get', 'status': 200,
                 'body': {'emailVerified': False}}]
        self.assertEqual(rows, exporter.delete_pair(rows, False))
        for altered in (rows[:1], [rows[0], {**rows[1], 'status': 401}],
                        [{**rows[0], 'body': {'code': 'AUTH_REQUIRED'}}, rows[1]]):
            with self.assertRaises(ValueError):
                exporter.delete_pair(altered, False)
        with self.assertRaises(ValueError):
            exporter.delete_pair(rows, True)


if __name__ == '__main__':
    unittest.main()
