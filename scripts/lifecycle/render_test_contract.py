"""Render a separate closed-test candidate; the canonical Starter file stays read-only."""
import argparse
import copy
from pathlib import Path
import sys

import yaml
from openapi_spec_validator import OpenAPIV31SpecValidator, validate

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_contract as canonical

ROOT = Path(__file__).resolve().parents[2]
GUARDED = (canonical.B2_OPERATIONS | canonical.B3_OPERATIONS) - canonical.AUTH_OPERATIONS - {
    ('get', '/me'), ('post', '/me/delete-account')}


def render_test_contract(base):
    canonical.check_project_invariants(base)
    api = copy.deepcopy(base)
    api['info']['version'] = '0.2.0-test.1'
    api['info']['description'] = 'Closed local N2 test candidate. Not the agreed mobile contract.'
    api['x-account-lifecycle-stage'] = 'closed-test-runtime'
    api['x-email-verification-enforced'] = True
    api['servers'] = [{'url': 'http://127.0.0.1:8081/api/v1', 'description': 'Isolated test only'}]
    api['components']['schemas']['Profile']['required'].append('emailVerified')
    for method, path in canonical.PLANNED_LIFECYCLE:
        operation = api['paths'][path][method]
        operation['x-implementation-status'] = 'test-only'
        operation['description'] = operation['description'].replace('PLANNED, не реализовано.', 'N2 closed test only.')
    for method, path in GUARDED:
        api['paths'][path][method]['responses']['403'] = {'$ref': '#/components/responses/EmailVerificationRequired'}
    api['paths']['/auth/login']['post']['responses']['403'] = {'$ref': '#/components/responses/PasswordRecoveryRequired'}
    return api


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'backend/target/lifecycle-test-openapi.yaml')
    args = parser.parse_args()
    # A typo must never overwrite a tracked contract, fixture, or source file.
    output = args.output.resolve()
    if output != ROOT / 'backend/target/lifecycle-test-openapi.yaml':
        parser.error('Output must be the ignored backend/target/lifecycle-test-openapi.yaml')
    base = yaml.safe_load((ROOT / 'api/openapi.yaml').read_text(encoding='utf-8'))
    candidate = render_test_contract(base)
    validate(candidate, cls=OpenAPIV31SpecValidator)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(yaml.safe_dump(candidate, allow_unicode=True, sort_keys=False), encoding='utf-8')
    print('PASS rendered closed-test candidate 0.2.0-test.1; canonical 0.1.1 unchanged')


if __name__ == '__main__':
    main()
