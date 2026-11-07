"""Curate actual N2 HTTP responses for mobile fixtures; never perform HTTP requests."""
import argparse
import json
from pathlib import Path
from uuid import UUID

import yaml
from render_test_contract import GUARDED
from validate_http import validate_responses

ROOT = Path(__file__).resolve().parents[2]


def anonymize(value, identities, key=''):
    if key in {'password', 'newPassword', 'Authorization', 'authorization', 'otp'}:
        raise ValueError('Credentials are forbidden in mobile examples')
    if key in {'accessToken', 'resetToken', 'challengeId'}:
        expected = {'accessToken': 'x', 'resetToken': 'R', 'challengeId': 'A'}[key] * 43
        if value != expected:
            raise ValueError('Input HTTP export contains an unredacted proof')
        return expected
    if key == 'code' and str(value).isascii() and str(value).isdigit():
        raise ValueError('OTP is forbidden in mobile examples')
    if isinstance(value, dict):
        return {k: anonymize(v, identities, k) for k, v in value.items()}
    if isinstance(value, list):
        return [anonymize(v, identities, key) for v in value]
    if not isinstance(value, str):
        return value
    if key == 'email':
        return identities.setdefault(('email', value), f'account-{len(identities) + 1}@example.invalid')
    if key == 'displayName':
        return 'Test account'
    try:
        UUID(value)
    except ValueError:
        return value
    return identities.setdefault(('uuid', value), f'00000000-0000-4000-8000-{len(identities) + 1:012d}')


def delete_pair(rows, verified):
    for first, second in zip(rows, rows[1:]):
        if (first['operationPath'], first['method'], first['status']) != ('/me/delete-account', 'post', 401):
            continue
        if ((first.get('body') or {}).get('code') == 'INVALID_CREDENTIALS'
                and (second['operationPath'], second['method'], second['status']) == ('/me', 'get', 200)
                and (second.get('body') or {}).get('emailVerified') is verified):
            return [first, second]
    raise ValueError('Missing actual contextual delete-password401 -> me200 sequence')


def build_examples(api, exports):
    count = validate_responses(api, exports)
    rows = [row for export in exports for row in export['responses']]
    if any('headers' not in row for row in rows):
        raise ValueError('Fresh N2 captures with actual allowlisted headers are required')
    selected = []

    def add(name, path, method, status, *, flag=None, code=None):
        for row in rows:
            body = row.get('body') or {}
            profile = body.get('user', body)
            if ((row['operationPath'], row['method'], row['status']) == (path, method, status)
                    and (flag is None or profile.get('emailVerified') is flag)
                    and (code is None or body.get('code') == code)):
                selected.append({**row, 'caseId': name})
                return
        raise ValueError(f'Missing actual N2 sample: {name}')

    add('pending-register', '/auth/register', 'post', 201, flag=False)
    add('pending-login', '/auth/login', 'post', 200, flag=False)
    add('verified-login', '/auth/login', 'post', 200, flag=True)
    add('pending-profile', '/me', 'get', 200, flag=False)
    add('verified-profile', '/me', 'get', 200, flag=True)
    for name, path, status, code in (
        ('verification-request-accepted', '/auth/email-verification/request', 202, None),
        ('verification-already-verified', '/auth/email-verification/request', 204, None),
        ('verification-confirm-success', '/auth/email-verification/confirm', 204, None),
        ('reset-request-accepted', '/auth/password-reset/request', 202, None),
        ('reset-verify-success', '/auth/password-reset/verify', 200, None),
        ('reset-confirm-success', '/auth/password-reset/confirm', 204, None),
        ('invalid-email-confirm', '/auth/email-verification/confirm', 400, 'EMAIL_ACTION_INVALID'),
        ('invalid-reset-verify', '/auth/password-reset/verify', 400, 'EMAIL_ACTION_INVALID'),
        ('invalid-reset-confirm', '/auth/password-reset/confirm', 400, 'EMAIL_ACTION_INVALID'),
        ('reset-required-login', '/auth/login', 403, 'PASSWORD_RESET_REQUIRED'),
        ('quota-retry-after', '/auth/email-verification/request', 429, 'RATE_LIMITED'),
        ('queue-unavailable', '/auth/password-reset/request', 503, 'SERVICE_UNAVAILABLE'),
    ):
        add(name, path, 'post', status, code=code)
    add('revoked-me', '/me', 'get', 401, code='AUTH_REQUIRED')
    for method, path in GUARDED:
        add(f'pending-guard:{method}:{path}', path, method, 403, code='EMAIL_NOT_VERIFIED')
    api_rows = next((e['responses'] for e in exports if e.get('testClass') == 'LifecycleApiIT'), None)
    if api_rows is None:
        raise ValueError('LifecycleApiIT provenance is required for the same-Bearer sequences')
    sequences = []
    for verified in (False, True):
        name = 'delete-password-' + ('verified' if verified else 'pending')
        pair = delete_pair(api_rows, verified)
        ids = [name + '-401', name + '-me-200']
        selected.extend({**row, 'caseId': case_id} for row, case_id in zip(pair, ids))
        sequences.append({'id': name, 'caseIds': ids, 'sameBearer': True,
                          'evidenceTest': 'LifecycleApiIT#wrongDeletePasswordPreserves'
                          + ('VerifiedSession' if verified else 'PendingSessionAndPrivateGuards')})
    result = {'source': 'N2 HTTP', 'candidateVersion': '0.2.0-test.1', 'canonicalVersion': '0.1.1',
              'capture': {'testClasses': [e['testClass'] for e in exports], 'responseCount': count,
                          'format': 'parsed response JSON + actual allowlisted headers; identities anonymized'},
              'responses': anonymize(selected, {}), 'sequences': sequences}
    validate_responses(api, [result], require_coverage=False)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--schema', type=Path, default=ROOT / 'backend/target/lifecycle-test-openapi.yaml')
    parser.add_argument('--responses', type=Path, default=ROOT / 'backend/target/n2-contract')
    parser.add_argument('--output', type=Path, default=ROOT / 'backend/target/n2-mobile-http-examples.json')
    args = parser.parse_args()
    allowed = {ROOT / 'backend/target/n2-mobile-http-examples.json', ROOT / 'docs/examples/n2-http/responses.json'}
    if args.output.resolve() not in allowed:
        parser.error('Output must be the ignored example file or docs/examples/n2-http/responses.json')
    exports = [{**json.loads(path.read_text(encoding='utf-8')), 'testClass': path.stem}
               for path in sorted(args.responses.glob('*.json'))]
    result = build_examples(yaml.safe_load(args.schema.read_text(encoding='utf-8')), exports)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'PASS {len(result["responses"])} curated actual N2 examples, both same-Bearer401/200 sequences, redaction and headers')


if __name__ == '__main__':
    main()
