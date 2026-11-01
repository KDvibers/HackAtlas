#!/usr/bin/env python3
"""Audit the pinned operator binary; block external readiness on findings or inventory gaps."""

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid

from container_policy import SCANNER_IMAGE
from run_container_audit import run, cleanup_cache

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/release/ops'))
from restic_tool import VERSION, verify_binary
from restore_review import require, timestamp


def evaluate(report):
    require(isinstance(report, dict), 'Expected an audit object.')
    results = report.get('Results', [])
    require(isinstance(results, list) and len(results) == 1 and results[0].get('Type') == 'gobinary',
            'Expected exactly one scanned Go binary.')
    packages = results[0].get('Packages')
    require(isinstance(packages, list) and packages and all(isinstance(p, dict) for p in packages),
            'Go package inventory is missing or malformed.')
    require({'github.com/restic/restic', 'stdlib'} <= {p.get('Name') for p in packages},
            'Restic main module or Go standard library is missing.')
    gaps = [p.get('Name') for p in packages if not isinstance(p.get('Version'), str)
            or not re.fullmatch(r'v?\d+\.\d+\.\d+(?:[+-].*)?', p['Version'])]
    vulnerabilities = results[0].get('Vulnerabilities', [])
    require(isinstance(vulnerabilities, list) and all(isinstance(v, dict) for v in vulnerabilities),
            'Malformed vulnerability inventory.')
    counts = Counter(v.get('Severity', 'UNKNOWN') for v in vulnerabilities)
    blocked = bool(gaps) or any(severity not in ('LOW', 'MEDIUM') for severity in counts)
    return {'packages': len(packages), 'counts': dict(counts), 'unversionedPackages': gaps,
            'inventoryComplete': not gaps, 'dependencyGatePassed': not blocked, 'publicReleaseReady': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restic', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    require(os.name == 'posix', 'Use Linux/WSL with Docker.')
    binary = verify_binary(args.restic)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cache = 'hackatlas-audit-' + uuid.uuid4().hex
    run(['docker', 'volume', 'create', '--label', 'hackatlas.audit=' + cache, cache])
    base = ['docker', 'run', '--rm', '--label', 'hackatlas.audit=' + cache, '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '-v', cache + ':/cache']
    try:
        run(base + ['--network', 'none', '--entrypoint', 'mkdir', SCANNER_IMAGE, '-p', '/cache/tmp'])
        run(base + ['-e', 'TMPDIR=/cache/tmp', SCANNER_IMAGE, 'image', '--cache-dir', '/cache',
                    '--download-db-only', '--no-progress'], output=output / 'download.log', timeout=300)
        database = json.loads(run(base + ['--network', 'none', '--entrypoint', 'cat', SCANNER_IMAGE, '/cache/db/metadata.json']))
        now = datetime.now(timezone.utc)
        require(database.get('Version') == 2
                and now - timedelta(hours=24) <= timestamp(database['UpdatedAt']) <= now + timedelta(minutes=5)
                and timestamp(database['NextUpdate']) > now, 'Scanner advisory database is stale or invalid.')
        # rootfs is intentional: filesystem mode disables individual binary analyzers.
        run(base + ['--network', 'none', '--tmpfs', '/tmp:rw,nosuid,nodev,size=512m',
                    '-v', str(binary) + ':/input/restic:ro', SCANNER_IMAGE, 'rootfs', '/input',
                    '--cache-dir', '/cache', '--skip-db-update', '--offline-scan', '--no-progress',
                    '--scanners', 'vuln', '--pkg-types', 'library', '--list-all-pkgs', '--format', 'json'],
            output=output / 'restic-trivy.json', timeout=240)
        raw = (output / 'restic-trivy.json').read_bytes()
        report = evaluate(json.loads(raw))
        report.update(scanner=SCANNER_IMAGE, checkedAt=datetime.now(timezone.utc).isoformat(),
                      database=database, resticVersion=VERSION,
                      binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                      reportSha256=hashlib.sha256(raw).hexdigest(),
                      limitations=['Dependency matches do not establish reachability.',
                                   'Trivy Go scanning does not establish absence of vulnerabilities in restic itself.'])
        (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report))
        return 0 if report['dependencyGatePassed'] else 1
    finally:
        cleanup_cache(cache)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('Recovery tool audit incomplete: ' + type(error).__name__, file=sys.stderr)
        sys.exit(2)
