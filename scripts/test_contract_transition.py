"""Regression checks for the transitional contract; no server operations are enabled."""

import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout

import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
import validate_contract as contract

ROOT = Path(__file__).resolve().parents[1]
LIFECYCLE_PUBLIC = {('/auth/password-reset/' + action) for action in ('request', 'verify', 'confirm')}
LIFECYCLE_PRIVATE = {('/auth/email-verification/' + action) for action in ('request', 'confirm')}


class TransitionalContractTests(unittest.TestCase):
    def setUp(self):
        self.api = yaml.safe_load((ROOT / 'api/openapi.yaml').read_text(encoding='utf-8'))

    def rejects(self, change):
        candidate = copy.deepcopy(self.api)
        change(candidate)
        with self.assertRaises(ValueError):
            contract.check_project_invariants(candidate)

    def validate(self, schema, value):
        Draft202012Validator({'$ref': '#/components/schemas/' + schema, 'components': self.api['components']},
                             format_checker=FormatChecker()).validate(value)

    def test_current_contract_keeps_lifecycle_planned(self):
        self.assertEqual('0.1.1', self.api['info']['version'])
        contract.check_project_invariants(self.api)
        for path in LIFECYCLE_PUBLIC | LIFECYCLE_PRIVATE:
            self.assertEqual('planned', self.api['paths'][path]['post']['x-implementation-status'])

    def test_planned_security_is_exact(self):
        for path in LIFECYCLE_PUBLIC | LIFECYCLE_PRIVATE:
            wanted = [] if path in LIFECYCLE_PUBLIC else [{'BearerSession': []}]
            self.assertEqual(wanted, self.api['paths'][path]['post']['security'])
            self.rejects(lambda a: a['paths'][path]['post'].update(security=[{'BearerSession': []}] if not wanted else []))

    def test_planned_marker_cannot_hide_an_unknown_or_existing_operation(self):
        extra = copy.deepcopy(self.api['paths']['/auth/password-reset/request'])
        self.rejects(lambda a: a['paths'].update({'/auth/extra': extra}))
        self.rejects(lambda a: a['paths']['/auth/register']['post'].update(
            {'x-phase': 'ACCOUNT_LIFECYCLE', 'x-implementation-status': 'planned'}))
        self.rejects(lambda a: a['paths']['/auth/password-reset/request']['post'].update({'x-phase': 'B2'}))

    def test_contract_preparation_cannot_claim_activation(self):
        self.rejects(lambda a: a.update({'x-email-verification-enforced': True}))
        self.rejects(lambda a: a['paths']['/auth/password-reset/request']['post'].update({'x-implementation-status': 'implemented'}))

    def test_existing_access_and_no_refresh_invariants_still_reject_drift(self):
        self.rejects(lambda a: a['paths']['/hackathons']['get'].update(security=[{'BearerSession': []}]))
        self.rejects(lambda a: a['paths']['/me']['get'].update(security=[]))
        extra = copy.deepcopy(self.api['paths']['/auth/password-reset/request'])
        self.rejects(lambda a: a['paths'].update({'/auth/refresh': extra}))
        self.rejects(lambda a: a['components']['securitySchemes']['BearerSession'].update(bearerFormat='JWT'))
        self.rejects(lambda a: a['components']['schemas']['AuthResponse']['properties'].update(resetToken={'type': 'string'}))

    def test_legacy_true_false_profiles_and_closed_types(self):
        profile = json.loads((ROOT / 'api/examples/profile.json').read_text())
        self.assertNotIn('emailVerified', profile)
        for value in [profile, {**profile, 'emailVerified': True}, {**profile, 'emailVerified': False}]:
            self.validate('Profile', value)
        for value in [{**profile, 'emailVerified': None}, {**profile, 'emailVerified': 'false'},
                      {**profile, 'unexpectedField': True}, {k: v for k, v in profile.items() if k != 'id'}]:
            with self.assertRaises(ValidationError): self.validate('Profile', value)

    def test_optional_does_not_become_default_or_remove_old_requirements(self):
        self.rejects(lambda a: a['components']['schemas']['Profile']['required'].append('emailVerified'))
        self.rejects(lambda a: a['components']['schemas']['Profile']['properties']['emailVerified'].update(default=True))
        self.rejects(lambda a: a['components']['schemas']['Profile']['properties']['emailVerified'].update(type=['boolean', 'null']))
        self.rejects(lambda a: a['components']['schemas']['Profile']['required'].remove('email'))

    def test_client_cannot_assign_verification_and_reset_is_not_auth(self):
        with self.assertRaises(ValidationError): self.validate('UpdateProfile', {'emailVerified': True})
        self.rejects(lambda a: a['components']['schemas']['UpdateProfile']['properties'].update(emailVerified={'type': 'boolean'}))
        self.assertNotIn('resetToken', self.api['components']['schemas']['AuthResponse']['properties'])
        valid = {'resetToken': 'R' * 43, 'newPassword': 'synthetic-example-passphrase'}
        self.validate('PasswordResetConfirmRequest', valid)
        for extra in ['email', 'userId', 'accessToken']:
            with self.assertRaises(ValidationError):
                self.validate('PasswordResetConfirmRequest', {**valid, extra: 'untrusted'})

    def test_codes_preserve_leading_zero_and_reject_other_forms(self):
        self.validate('EmailCodeRequest', {'challengeId': 'A' * 43, 'code': '01234567'})
        for value in [12345678, '1234567', '１２３４５６７８', '1234 5678']:
            with self.assertRaises(ValidationError):
                self.validate('EmailCodeRequest', {'challengeId': 'A' * 43, 'code': value})

    def test_new_codes_do_not_remove_existing_errors(self):
        codes = self.api['components']['schemas']['LifecycleProblem']['properties']['code']['enum']
        self.assertTrue({'EMAIL_NOT_VERIFIED', 'EMAIL_ACTION_INVALID', 'PASSWORD_RESET_REQUIRED'} <= set(codes))
        self.rejects(lambda a: a['components']['schemas']['Problem']['properties']['code']['enum'].remove('AUTH_REQUIRED'))

    def test_current_problem_and_response_statuses_are_not_widened(self):
        codes = self.api['components']['schemas']['Problem']['properties']['code']['enum']
        self.assertEqual(9, len(codes))
        self.assertNotIn('EMAIL_NOT_VERIFIED', codes)
        self.assertNotIn('403', self.api['paths']['/auth/login']['post']['responses'])
        self.rejects(lambda a: a['components']['schemas']['Problem']['properties']['code']['enum'].append('EMAIL_NOT_VERIFIED'))

    def test_fixture_manifests_are_independent(self):
        legacy = json.loads((ROOT / 'api/examples/manifest.json').read_text())
        self.assertEqual(6, len(legacy))
        with redirect_stdout(io.StringIO()):
            self.assertEqual(13, contract.validate_example_manifests(self.api, ROOT / 'api/examples'))

    def test_fixture_manifest_cannot_escape_its_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            import shutil
            destination = Path(temporary) / 'examples'
            shutil.copytree(ROOT / 'api/examples', destination)
            manifest = destination / 'account-lifecycle/manifest.json'
            value = json.loads(manifest.read_text())
            value['../../outside.json'] = 'Profile'
            manifest.write_text(json.dumps(value))
            with redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
                contract.validate_example_manifests(self.api, destination)


if __name__ == '__main__':
    unittest.main()
