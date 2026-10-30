"""Private recovery bundles and the real pinned local restic CLI."""

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/release/ops'))
from recovery_bundle import stage_bundle, validate_bundle
from recovery_archive import Archive
from restic_tool import verify_download


class PrivateFixtures:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hackatlas-b45-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir(mode=0o700)
        self.bundle = self.root / 'bundle'
        self.bundle.mkdir(mode=0o700)
        self.export = self.source / 'private.deletion-export.json'
        self.export.write_text(json.dumps({
            'formatVersion': 1, 'sourceProject': 'hackatlas-test', 'sourceDatabase': 'hackatlas',
            'exportedAt': datetime.now(timezone.utc).isoformat(), 'accountCount': 1,
            'deletedAccountIds': ['11111111-2222-4333-8444-555555555555'], 'historyComplete': False}))
        self.export.chmod(0o600)
        self.backup = self.source / 'private.dump'
        self.backup.write_bytes(b'PGDMP\x01synthetic-test-dump')
        self.backup.chmod(0o600)
        self.checksum = self.source / 'private.dump.sha256'
        self.checksum.write_text(hashlib.sha256(self.backup.read_bytes()).hexdigest() + '\n')
        self.checksum.chmod(0o600)

    def stage(self, backup=True):
        return stage_bundle(self.bundle, 'hackatlas-test', 'hackatlas', self.export,
                            self.backup if backup else None)

    def validate(self):
        return validate_bundle(self.bundle, 'hackatlas-test', 'hackatlas')


class RecoveryBundleTests(PrivateFixtures, unittest.TestCase):
    def test_backup_roundtrip_and_export_only_are_distinct(self):
        manifest = self.stage()
        self.assertEqual(manifest, self.validate())
        self.assertEqual('backup', manifest['kind'])
        self.assertFalse(manifest['historyComplete'])
        self.assertEqual(self.backup.read_bytes(), (self.bundle / 'database.dump').read_bytes())
        for path in self.bundle.iterdir():
            self.assertEqual(0, path.stat().st_mode & 0o077)
        other = self.root / 'other'
        other.mkdir(mode=0o700)
        value = stage_bundle(other, 'hackatlas-test', 'hackatlas', self.export)
        self.assertEqual('deletions', value['kind'])
        self.assertEqual({'source.deletion-export.json'}, set(value['files']))

    def test_checksum_mismatch_and_missing_checksum_refused(self):
        self.checksum.write_text('0' * 64 + '\n')
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse((self.bundle / 'manifest.json').exists())
        self.checksum.unlink()
        with self.assertRaises((ValueError, OSError)):
            self.stage()

    def test_invalid_dump_header_refused_even_with_matching_checksum(self):
        self.backup.write_bytes(b'plain secret text')
        self.checksum.write_text(hashlib.sha256(self.backup.read_bytes()).hexdigest() + '\n')
        with self.assertRaises(ValueError):
            self.stage()

    def test_input_public_directory_or_file_refused(self):
        self.source.chmod(0o755)
        with self.assertRaises(ValueError):
            self.stage()
        self.source.chmod(0o700)
        self.export.chmod(0o644)
        with self.assertRaises(ValueError):
            self.stage(False)

    def test_symlink_fifo_and_hardlink_inputs_refused_without_blocking(self):
        original = self.source / 'original'
        self.export.rename(original)
        for kind in ['symlink', 'fifo', 'hardlink']:
            if kind == 'symlink':
                self.export.symlink_to(original)
            elif kind == 'fifo':
                os.mkfifo(self.export, 0o600)
            else:
                os.link(original, self.export)
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                self.stage(False)
            self.export.unlink()

    def test_no_overwrite_or_unrelated_input(self):
        sentinel = self.bundle / 'keep'
        sentinel.write_text('preserve')
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual('preserve', sentinel.read_text())

    def test_wrong_source_and_duplicate_json_keys_refused(self):
        value = json.loads(self.export.read_text())
        value['sourceProject'] = 'other'
        self.export.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            self.stage(False)
        self.export.write_text('{"formatVersion":1,"formatVersion":1}')
        with self.assertRaises(ValueError):
            self.stage(False)

    def test_manifest_strictness_and_no_traversal(self):
        self.stage()
        path = self.bundle / 'manifest.json'
        original = json.loads(path.read_text())
        mutations = [lambda r: r.update(extra=1), lambda r: r.update(formatVersion=True),
                     lambda r: r.update(historyComplete=True), lambda r: r.update(kind='other'),
                     lambda r: r.update(sourceProject='other'), lambda r: r.update(createdAt='yesterday'),
                     lambda r: r['files'].update({'../escape': {'size': 1, 'sha256': '0' * 64}}),
                     lambda r: r['files']['database.dump'].update(size=True),
                     lambda r: r['files']['database.dump'].update(sha256='0' * 64)]
        for mutate in mutations:
            value = json.loads(json.dumps(original))
            mutate(value)
            path.write_text(json.dumps(value))
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                self.validate()

    def test_corrupt_missing_extra_files_and_duplicate_manifest_refused(self):
        self.stage()
        path = self.bundle / 'database.dump'
        original = path.read_bytes()
        path.write_bytes(original + b'x')
        with self.assertRaises(ValueError):
            self.validate()
        path.write_bytes(original)
        extra = self.bundle / '.env'
        extra.write_text('must not be archived')
        with self.assertRaises(ValueError):
            self.validate()
        extra.unlink()
        path.unlink()
        with self.assertRaises(ValueError):
            self.validate()
        (self.bundle / 'manifest.json').write_text('{"kind":"backup","kind":"backup"}')
        with self.assertRaises(ValueError):
            self.validate()

    def test_oversize_export_and_manifest_are_refused(self):
        self.export.write_bytes(b' ' * (1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            self.stage(False)
        (self.bundle / 'manifest.json').write_bytes(b' ' * (16 * 1024 + 1))
        (self.bundle / 'manifest.json').chmod(0o600)
        with self.assertRaises(ValueError):
            self.validate()

    def test_untrusted_download_refused(self):
        with self.assertRaises(ValueError):
            verify_download(b'not the official compressed binary')


class ResticArchiveTests(PrivateFixtures, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.binary = ROOT / 'backend/target/restic-0.19.1'
        self.repo = self.root / 'repository'
        self.password = self.root / 'archive-password'
        self.password.write_text('synthetic-archive-key-' + os.urandom(24).hex() + '\n')
        self.password.chmod(0o600)
        with self.archive() as archive:
            archive.init()

    def archive(self, password=None, repository=None):
        return Archive(self.binary, repository or self.repo, password or self.password)

    def store(self, backup=True):
        with self.archive() as archive:
            return archive.store('hackatlas-test', 'hackatlas', self.export, self.backup if backup else None)

    def test_real_roundtrip_export_only_and_read_all_data(self):
        (self.source / '.env').write_text('synthetic-excluded-content')
        first = self.store()
        self.assertEqual(64, len(first['snapshotId']))
        self.assertFalse(first['historyComplete'])
        self.export.write_text(self.export.read_text().replace('11111111', '22222222'))
        later = self.store(False)
        self.assertNotEqual(first['snapshotId'], later['snapshotId'])
        with self.archive() as archive:
            archive.check()
            output = self.root / 'extracted'
            archive.extract(first['snapshotId'], output, 'hackatlas-test', 'hackatlas')
            self.assertEqual(self.backup.read_bytes(), (output / 'database.dump').read_bytes())
            self.assertFalse((output / '.env').exists())
            newer = self.root / 'newer'
            archive.extract(later['snapshotId'], newer, 'hackatlas-test', 'hackatlas')
            self.assertEqual(self.export.read_bytes(), (newer / 'source.deletion-export.json').read_bytes())
            self.assertFalse((newer / 'database.dump').exists())

    def test_unexpected_file_in_foreign_snapshot_refused(self):
        stage_bundle(self.bundle, 'hackatlas-test', 'hackatlas', self.export)
        extra = self.bundle / '.env'
        extra.write_text('synthetic-unexpected-file')
        extra.chmod(0o600)
        with self.archive() as archive:
            raw = archive.run(['backup', '--', '.'], cwd=self.bundle)
            snapshot = [json.loads(line)['snapshot_id'] for line in raw.splitlines()
                        if json.loads(line).get('message_type') == 'summary'][0]
            with self.assertRaises(ValueError):
                archive.extract(snapshot, self.root / 'foreign', 'hackatlas-test', 'hackatlas')
            self.assertFalse((self.root / 'foreign').exists())

    def test_wrong_password_source_snapshot_and_no_overwrite(self):
        snapshot = self.store()['snapshotId']
        wrong = self.root / 'wrong-password'
        wrong.write_text('wrong-synthetic-password\n')
        wrong.chmod(0o600)
        with self.archive(password=wrong) as archive, self.assertRaises(ValueError):
            archive.check()
        with self.archive() as archive:
            for invalid in ['latest', snapshot[:8], '0' * 64]:
                with self.subTest(snapshot=invalid), self.assertRaises(ValueError):
                    archive.extract(invalid, self.root / 'invalid', 'hackatlas-test', 'hackatlas')
            with self.assertRaises(ValueError):
                archive.extract(snapshot, self.root / 'wrong-source', 'other', 'hackatlas')
            self.assertFalse((self.root / 'wrong-source').exists())
            output = self.root / 'extracted'
            archive.extract(snapshot, output, 'hackatlas-test', 'hackatlas')
            with self.assertRaises((ValueError, FileExistsError)):
                archive.extract(snapshot, output, 'hackatlas-test', 'hackatlas')
            self.assertEqual(self.backup.read_bytes(), (output / 'database.dump').read_bytes())
            with self.assertRaises(ValueError):
                archive.init()

    def test_environment_hook_is_not_executed(self):
        from unittest.mock import patch
        marker = self.root / 'hook-executed'
        with patch.dict(os.environ, {'RESTIC_PASSWORD_COMMAND': 'touch ' + str(marker),
                                     'RESTIC_REPOSITORY': 'sftp:invalid:/repo'}):
            self.store(False)
        self.assertFalse(marker.exists())

    def test_corrupt_repository_refused_and_cli_errors_hide_data(self):
        self.store()
        packs = [p for p in (self.repo / 'data').rglob('*') if p.is_file()]
        self.assertTrue(packs)
        packs[0].write_bytes(b'corrupt-synthetic-pack')
        command = [sys.executable, str(ROOT / 'infra/release/ops/recovery_archive.py'),
                   '--restic', str(self.binary), '--repository', str(self.repo),
                   '--password-file', str(self.password), 'check']
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
        self.assertNotEqual(0, result.returncode)
        for secret in [self.password.read_text().strip(), '11111111-2222-4333-8444-555555555555', str(packs[0])]:
            self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertFalse(result.stdout)

    def test_paths_and_secret_must_be_separate(self):
        with self.assertRaises(ValueError):
            with self.archive(repository=Path('sftp:host:/repo')):
                pass
        inside = self.repo / 'password'
        inside.write_bytes(self.password.read_bytes())
        inside.chmod(0o600)
        with self.assertRaises(ValueError):
            with self.archive(password=inside):
                pass
        with self.archive() as archive, self.assertRaises(ValueError):
            archive.store('hackatlas-test', 'hackatlas', self.password)

if __name__ == '__main__':
    unittest.main()
