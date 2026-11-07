"""Check actual N2 HTTP exports against the isolated candidate and its error matrix."""
import argparse
import json
from pathlib import Path
from uuid import UUID

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from render_test_contract import GUARDED

VERIFICATION = '/auth/email-verification/'
RESET = '/auth/password-reset/'
REQUIRED = {(VERIFICATION + 'request', 'post', status) for status in (202, 204, 400, 429)} | {
    (VERIFICATION + 'confirm', 'post', status) for status in (204, 400)} | {
    (RESET + 'request', 'post', status) for status in (202, 400, 503)} | {
    (RESET + 'verify', 'post', status) for status in (200, 400)} | {
    (RESET + 'confirm', 'post', status) for status in (204, 400)} | {
    (path, method, 403) for method, path in GUARDED} | {
    ('/auth/login', 'post', 403), ('/me', 'get', 200), ('/me', 'get', 401)}
REQUIRED.add(('/me/delete-account', 'post', 401))


def validate_captured_headers(row):
    """Older exports have selected flat fields; new N2 captures include this allowlist."""
    if 'headers' not in row:
        return
    source = row['headers']
    if not isinstance(source, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in source.items()):
        raise ValueError('Captured headers must be a string map')
    headers = {key.lower(): value for key, value in source.items()}
    allowed = {'content-type', 'cache-control', 'retry-after', 'x-request-id', 'www-authenticate'}
    if len(headers) != len(source) or set(headers) - allowed:
        raise ValueError('Unapproved or duplicate header in N2 export')
    for flat, header in (('contentType', 'content-type'), ('cacheControl', 'cache-control'), ('retryAfter', 'retry-after')):
        if headers.get(header, '') != row.get(flat, ''):
            raise ValueError('Captured headers disagree with selected header fields')
    request_id = headers.get('x-request-id')
    try:
        UUID(request_id)
    except (ValueError, TypeError, AttributeError):
        raise ValueError('Captured X-Request-Id must be a UUID') from None
    if row['status'] >= 400 and (row.get('body') or {}).get('requestId') != request_id:
        raise ValueError('Problem.requestId differs from captured X-Request-Id')


def validate_responses(api, exports, *, require_coverage=True):
    if (api.get('x-account-lifecycle-stage') != 'closed-test-runtime'
            or api.get('x-email-verification-enforced') is not True):
        raise ValueError('Only the isolated N2 candidate may validate these exports')
    if not exports or any(export.get('source') != 'N2 HTTP' or not export.get('responses') for export in exports):
        raise ValueError('Expected non-empty actual N2 HTTP exports')
    coverage = set()
    count = 0
    for export in exports:
        for row in export['responses']:
            validate_captured_headers(row)
            path, method, status = row['operationPath'], row['method'], row['status']
            operation = api['paths'].get(path, {}).get(method)
            if operation is None or str(status) not in operation['responses']:
                raise ValueError(f'Undeclared N2 response: {method} {path} {status}')
            response = operation['responses'][str(status)]
            if '$ref' in response:
                response = api['components']['responses'][response['$ref'].rsplit('/', 1)[1]]
            coverage.add((path, method, status))
            count += 1
            if path.startswith('/auth/') and status < 400 or status in (401, 403) or path.startswith((VERIFICATION, RESET)):
                if 'no-store' not in row.get('cacheControl', '').lower():
                    raise ValueError(f'{path} {status} must use no-store')
            if status == 429 and not row.get('retryAfter', '').isdigit():
                raise ValueError('429 must carry Retry-After seconds')
            body = row.get('body')
            if status == 204:
                if body is not None or response.get('content'):
                    raise ValueError('204 must have no body')
                continue
            media = row.get('contentType', '').split(';', 1)[0].strip().lower()
            if media not in response.get('content', {}):
                raise ValueError(f'Undeclared media type for {path} {status}')
            schema = {'components': api['components'], **response['content'][media]['schema']}
            errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))
            if errors:
                # Do not include actual response values: exports must already be redacted.
                raise ValueError(f'Candidate schema mismatch for {method} {path} {status}')
            for key, placeholder in (('accessToken', 'x' * 43), ('resetToken', 'R' * 43), ('challengeId', 'A' * 43)):
                if key in body and body[key] != placeholder:
                    raise ValueError(f'Unredacted {key} in HTTP export')
    if require_coverage and (missing := REQUIRED - coverage):
        raise ValueError(f'Missing actual N2 response coverage: {sorted(missing)}')
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--schema', type=Path, required=True)
    parser.add_argument('--responses', type=Path, required=True)
    args = parser.parse_args()
    api = yaml.safe_load(args.schema.read_text(encoding='utf-8'))
    exports = [json.loads(path.read_text(encoding='utf-8')) for path in sorted(args.responses.glob('*.json'))]
    print(f'PASS {validate_responses(api, exports)} actual N2 responses: candidate, error matrix, headers, redaction and coverage')


if __name__ == '__main__':
    main()
