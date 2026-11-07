"""The closed-test contract must not silently change the mobile agreement."""
import copy
import json
from pathlib import Path
import sys
import unittest

import yaml
from jsonschema import Draft202012Validator, ValidationError
from openapi_spec_validator import OpenAPIV31SpecValidator, validate

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_contract as canonical
from render_test_contract import render_test_contract, GUARDED
from validate_http import validate_responses

ROOT = Path(__file__).resolve().parents[2]


class TestContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = yaml.safe_load((ROOT / 'api/openapi.yaml').read_text(encoding='utf-8'))

    def test_renderer_preserves_canonical_and_exact_surface(self):
        original = copy.deepcopy(self.base)
        candidate = render_test_contract(self.base)
        self.assertEqual(original, self.base)
        canonical.check_project_invariants(self.base)
        validate(candidate, cls=OpenAPIV31SpecValidator)
        self.assertEqual(set(original['paths']), set(candidate['paths']))
        self.assertEqual('0.2.0-test.1', candidate['info']['version'])
        self.assertEqual('closed-test-runtime', candidate['x-account-lifecycle-stage'])
        self.assertTrue(candidate['x-email-verification-enforced'])
        self.assertEqual(5, sum(op.get('x-implementation-status') == 'test-only'
                               for item in candidate['paths'].values() for op in item.values()
                               if isinstance(op, dict)))
        self.assertEqual(original['components']['securitySchemes'], candidate['components']['securitySchemes'])
        self.assertEqual(original['components']['schemas']['Problem'], candidate['components']['schemas']['Problem'])

    def test_unapproved_base_drift_is_rejected(self):
        for change in (lambda api: api['info'].update(version='0.2.0'),
                       lambda api: api['paths'].update({'/auth/refresh': copy.deepcopy(api['paths']['/auth/login'])}),
                       lambda api: api['paths']['/me']['get'].update(security=[])):
            altered = copy.deepcopy(self.base)
            change(altered)
            with self.assertRaises(ValueError):
                render_test_contract(altered)

    def test_verified_flag_is_required_boolean_only_in_candidate(self):
        api = render_test_contract(self.base)
        profile = json.loads((ROOT / 'api/examples/profile.json').read_text(encoding='utf-8'))
        validator = Draft202012Validator({'$ref': '#/components/schemas/Profile', 'components': api['components']})
        for value in (True, False):
            validator.validate({**profile, 'emailVerified': value})
        for value in (None, 'true', 1):
            with self.assertRaises(ValidationError):
                validator.validate({**profile, 'emailVerified': value})
        with self.assertRaises(ValidationError):
            validator.validate(profile)

    def test_reset_required_is_login_only_and_pending_errors_are_scoped(self):
        api = render_test_contract(self.base)
        problem = json.loads((ROOT / 'api/examples/account-lifecycle/password-reset-required.json').read_text(encoding='utf-8'))
        def response_schema(path, method, status):
            response = api['paths'][path][method]['responses'][str(status)]
            if '$ref' in response:
                response = api['components']['responses'][response['$ref'].rsplit('/', 1)[1]]
            return {'components': api['components'], **response['content']['application/problem+json']['schema']}
        Draft202012Validator(response_schema('/auth/login', 'post', 403)).validate(problem)
        pending = {**problem, 'code': 'EMAIL_NOT_VERIFIED'}
        for method, path in GUARDED:
            validator = Draft202012Validator(response_schema(path, method, 403))
            validator.validate(pending)
            with self.assertRaises(ValidationError):
                validator.validate(problem)
        self.assertNotIn('403', api['paths']['/me']['get']['responses'])
        with self.assertRaises(ValidationError):
            Draft202012Validator(response_schema('/me', 'get', 401)).validate({**problem, 'status': 401})

    def test_http_validator_rejects_undeclared_error_and_unredacted_proof(self):
        api = render_test_contract(self.base)
        base = {'operationPath': '/auth/password-reset/request', 'method': 'post', 'status': 202,
                'cacheControl': 'no-store', 'contentType': 'application/json', 'retryAfter': '',
                'body': {'challengeId': 'A' * 43, 'expiresAt': '2026-11-05T10:00:00Z', 'resendAfterSeconds': 60}}
        validate_responses(api, [{'source': 'N2 HTTP', 'responses': [base]}], require_coverage=False)
        for change in (lambda row: row['body'].update(challengeId='B' * 43),
                       lambda row: row.update(cacheControl='public'),
                       lambda row: row.update(status=418)):
            row = copy.deepcopy(base)
            change(row)
            with self.assertRaises(ValueError):
                validate_responses(api, [{'source': 'N2 HTTP', 'responses': [row]}], require_coverage=False)

    def test_http_validator_requires_real_exports_and_full_coverage(self):
        api = render_test_contract(self.base)
        for exports in ([], [{'source': 'B2 HTTP', 'responses': [{}]}], [{'source': 'N2 HTTP', 'responses': []}]):
            with self.assertRaises(ValueError):
                validate_responses(api, exports)

    def test_captured_headers_must_match_and_must_not_include_credentials(self):
        api = render_test_contract(self.base)
        base = {'operationPath': '/auth/password-reset/request', 'method': 'post', 'status': 202,
                'cacheControl': 'no-store', 'contentType': 'application/json', 'retryAfter': '',
                'headers': {'Content-Type': 'application/json', 'Cache-Control': 'no-store',
                            'X-Request-Id': '00000000-0000-4000-8000-000000000001'},
                'body': {'challengeId': 'A' * 43, 'expiresAt': '2026-11-05T10:00:00Z', 'resendAfterSeconds': 60}}
        validate_responses(api, [{'source': 'N2 HTTP', 'responses': [base]}], require_coverage=False)
        for key, value in (('Cache-Control', 'public'), ('Retry-After', '60'),
                           ('X-Request-Id', 'invalid'), ('Set-Cookie', 'session=redacted'),
                           ('Authorization', 'Bearer placeholder')):
            row = copy.deepcopy(base)
            row['headers'][key] = value
            with self.subTest(header=key), self.assertRaises(ValueError):
                validate_responses(api, [{'source': 'N2 HTTP', 'responses': [row]}], require_coverage=False)


if __name__ == '__main__':
    unittest.main()
