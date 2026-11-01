"""Real recovery-archive exercises sharing only this rehearsal's disposable resources."""

import hashlib
import json
from pathlib import Path
import secrets
import sys

ROOT = Path(__file__).resolve().parents[2]
OPS = ROOT / 'infra/release/ops'


class RecoveryArchiveChecks:
    def __init__(self, rehearsal, require):
        self.r = rehearsal
        self.require = require
        self.password = rehearsal.temp / 'archive-password'
        self.password.write_text(secrets.token_hex(32) + '\n', encoding='ascii')
        self.password.chmod(0o600)
        rehearsal.redactions.append(self.password.read_text().strip())
        self.command = [sys.executable, str(OPS / 'recovery_archive.py'), '--restic',
                        str(ROOT / 'backend/target/restic-0.19.1'), '--repository',
                        str(rehearsal.temp / 'restic-repository'), '--password-file', str(self.password)]
        self.source = ['--source-project', rehearsal.project, '--source-database', 'hackatlas']

    def run(self, label, *args):
        return json.loads(self.r.run(label, [*self.command, *args], timeout=300).stdout)

    def save_backup(self, backup):
        early = self.r.temp / 'before-delete.deletion-export.json'
        self.r.run('Export receipts accompanying old backup', [sys.executable, str(OPS / 'export_deletions.py'),
                   '--project', self.r.project, '--output', str(early)])
        self.run('Initialize disposable encrypted local repository', 'init')
        self.backup = self.run('Encrypt and read back actual PostgreSQL backup', 'store', *self.source,
                               '--export', str(early), '--backup', str(backup))
        self.early_export = early.read_bytes()

    def save_later_and_extract(self, backup, export):
        later = self.run('Encrypt a later export independently from backup', 'store', *self.source,
                         '--export', str(export))
        check = self.run('Read and verify all encrypted repository data', 'check')
        restored = self.r.temp / 'archive-backup-output'
        restored_later = self.r.temp / 'archive-export-output'
        first = self.run('Extract exact old backup snapshot', 'extract', *self.source,
                         '--snapshot', self.backup['snapshotId'], '--output', str(restored))
        second = self.run('Extract exact later deletion snapshot', 'extract', *self.source,
                          '--snapshot', later['snapshotId'], '--output', str(restored_later))
        require = self.require
        require(backup.read_bytes() == (restored / 'database.dump').read_bytes(), 'Archive backup bytes must match')
        require((restored / 'source.deletion-export.json').read_bytes() == self.early_export,
                'Old archive history must remain an old snapshot')
        require(export.read_bytes() == (restored_later / 'source.deletion-export.json').read_bytes()
                and export.read_bytes() != self.early_export, 'Later history must come from its separate snapshot')
        require(not (restored_later / 'database.dump').exists(), 'Export-only archive must not contain a backup')
        require(all(item.get('historyComplete') is False for item in (self.backup, later, first, second, check)),
                'Archive operations must never attest complete deletion history')
        require(first['runtimeAccessGranted'] is False and second['runtimeAccessGranted'] is False,
                'Extracting files must never open a database')
        self.r.report['recoveryArchive'] = {
            'resticVersion': '0.19.1', 'localOnly': True, 'backupSnapshotId': self.backup['snapshotId'],
            'laterExportSnapshotId': later['snapshotId'], 'dataReadVerified': check['dataReadVerified'],
            'backupSha256': hashlib.sha256(backup.read_bytes()).hexdigest(),
            'laterHistorySeparate': True, 'extractedBytesMatch': True, 'historyComplete': False,
            'automaticApproval': False}
        return restored / 'database.dump', restored_later / 'source.deletion-export.json'
