#!/usr/bin/env python3
"""Local encrypted recovery snapshots. Never approves a database or complete deletion history."""

import argparse
import io
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import tempfile
import time

from recovery_bundle import (LIMITS, copy_private, identity, local_path, private_directory, private_file,
                             stage_bundle, validate_bundle, validate_manifest)
from restic_tool import VERSION, verify_binary
from restore_review import read_private_review, require, unique_object


class Archive:
    def __init__(self, binary, repository, password_file):
        self.binary = verify_binary(binary)
        require(Path(repository).is_absolute(), 'Use an absolute local repository directory; remote backends are disabled.')
        self.repository = local_path(repository)
        private_directory(self.repository.parent)
        self.password = local_path(password_file)
        require(not self.password.is_relative_to(self.repository), 'Password must be outside the repository.')
        self.workspace = None

    def __enter__(self):
        # Capture a private key copy before spawning restic; never inherit credential hooks.
        with private_file(self.password, 4096) as source:
            password = source.read(4097)
        require(16 <= len(password.strip()) <= 4096, 'Use a separate nonempty archive password of at least 16 bytes.')
        self.workspace = tempfile.TemporaryDirectory(prefix='hackatlas-archive-', dir=self.repository.parent)
        self.temp = Path(self.workspace.name)
        self.key = self.temp / 'password'
        descriptor = os.open(self.key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(password)
        return self

    def __exit__(self, *unused):
        if self.workspace is not None:
            self.workspace.cleanup()

    def run(self, arguments, *, cwd=None, destination=None, maximum=2 * 1024 * 1024):
        require(self.workspace is not None, 'Use the archive context manager.')
        command = [str(self.binary), '--repo', str(self.repository), '--password-file', str(self.key),
                   '--no-cache', '--json', *arguments]
        env = {'PATH': os.defpath, 'HOME': str(self.temp), 'TMPDIR': str(self.temp), 'LANG': 'C.UTF-8'}
        buffer = destination if destination is not None else io.BytesIO()
        with subprocess.Popen(command, cwd=cwd or self.temp, env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL) as process:
            try:
                count, deadline = 0, time.monotonic() + 1800
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    while True:
                        require(time.monotonic() < deadline, 'Restic command timed out.')
                        if not selector.select(timeout=1):
                            continue
                        block = os.read(process.stdout.fileno(), 65536)
                        if not block:
                            break
                        count += len(block)
                        require(count <= maximum, 'Restic output exceeds the permitted limit.')
                        buffer.write(block)
                require(process.wait(timeout=max(1, deadline - time.monotonic())) == 0,
                        'Restic command failed; no successful archive operation is confirmed.')
            except BaseException:
                if process.poll() is None:
                    process.kill()
                process.wait()
                raise
        return buffer.getvalue() if destination is None else None

    def init(self):
        require(not self.repository.exists(), 'Repository already exists; init never overwrites it.')
        self.repository.mkdir(mode=0o700)
        self.run(['init', '--repository-version', '2'])
        return {'initialized': True, 'localOnly': True, 'resticVersion': VERSION}

    def check(self):
        private_directory(self.repository)
        self.run(['check', '--read-data'])
        return {'dataReadVerified': True, 'historyComplete': False, 'localOnly': True}

    def store(self, project, database, export, backup=None):
        private_directory(self.repository)
        identity(project, database)
        for value in [export] + ([backup, str(backup) + '.sha256'] if backup is not None else []):
            path = local_path(value)
            require(path != self.password and not path.is_relative_to(self.repository),
                    'Archive inputs must be separate from the key and repository.')
        with tempfile.TemporaryDirectory(prefix='bundle-', dir=self.temp) as name:
            bundle = Path(name)
            manifest = stage_bundle(bundle, project, database, export, backup)
            raw = self.run(['backup', '--host', 'hackatlas-local', '--tag', 'hackatlas-recovery-v1', '--', '.'], cwd=bundle)
            summaries = [value for line in raw.splitlines() if (value := json.loads(line)).get('message_type') == 'summary']
            require(len(summaries) == 1, 'Restic did not return one backup summary.')
            snapshot = summaries[0].get('snapshot_id')
            require(isinstance(snapshot, str) and re.fullmatch(r'[0-9a-f]{64}', snapshot), 'Missing full snapshot ID.')
            # Read the saved bytes back before reporting a verified store.
            self.extract(snapshot, self.temp / ('verify-' + snapshot), project, database)
        return {'snapshotId': snapshot, 'kind': manifest['kind'], 'storedAndReadVerified': True,
                'historyComplete': False, 'localOnly': True}

    def extract(self, snapshot, output, project, database):
        private_directory(self.repository)
        identity(project, database)
        require(isinstance(snapshot, str) and re.fullmatch(r'[0-9a-f]{64}', snapshot),
                'Use an explicit full snapshot ID; latest and abbreviated IDs are refused.')
        output = local_path(output)
        private_directory(output.parent)
        require(not output.exists() and not output.is_relative_to(self.repository)
                and not self.repository.is_relative_to(output) and not self.password.is_relative_to(output),
                'Extraction requires a new separate private directory; no overwrite is allowed.')
        with tempfile.TemporaryDirectory(prefix='extract-', dir=self.temp) as name:
            staged = Path(name)
            manifest_path = staged / 'manifest.json'
            self.dump(snapshot, 'manifest.json', manifest_path, LIMITS['manifest.json'])
            manifest = validate_manifest(read_private_review(manifest_path), project, database)
            records = [json.loads(line, object_pairs_hook=unique_object)
                       for line in self.run(['ls', snapshot]).splitlines()]
            nodes = [r for r in records if r.get('struct_type') == 'node']
            expected = {'/' + n for n in ('manifest.json', *manifest['files'])}
            require(len(nodes) == len(expected) and {r.get('path') for r in nodes} == expected
                    and all(r.get('type') == 'file' for r in nodes), 'Snapshot contains unexpected paths or file types.')
            for filename, attributes in manifest['files'].items():
                self.dump(snapshot, filename, staged / filename, attributes['size'])
            validate_bundle(staged, project, database)
            # mkdir is exclusive even if another operator creates output during verification.
            output.mkdir(mode=0o700)
            for path in staged.iterdir():
                copy_private(path, output / path.name, LIMITS[path.name])
            validate_bundle(output, project, database)
            for directory in (output, output.parent):
                fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
        return {'snapshotId': snapshot, 'kind': manifest['kind'], 'extractedVerified': True,
                'historyComplete': False, 'runtimeAccessGranted': False, 'localOnly': True}

    def dump(self, snapshot, name, output, maximum):
        descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as destination:
            self.run(['dump', snapshot, '/' + name], destination=destination, maximum=maximum)
            destination.flush()
            os.fsync(destination.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restic', type=Path, required=True)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    actions = parser.add_subparsers(dest='action', required=True)
    actions.add_parser('init')
    actions.add_parser('check')
    store = actions.add_parser('store')
    store.add_argument('--export', dest='export_path', type=Path, required=True)
    store.add_argument('--backup', type=Path)
    extract = actions.add_parser('extract')
    extract.add_argument('--snapshot', required=True)
    extract.add_argument('--output', type=Path, required=True)
    for action in (store, extract):
        action.add_argument('--source-project', required=True)
        action.add_argument('--source-database', required=True, choices=['hackatlas'])
    args = parser.parse_args()
    with Archive(args.restic, args.repository, args.password_file) as archive:
        if args.action == 'store':
            result = archive.store(args.source_project, args.source_database, args.export_path, args.backup)
        elif args.action == 'extract':
            result = archive.extract(args.snapshot, args.output, args.source_project, args.source_database)
        else:
            result = getattr(archive, args.action)()
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Never echo restic output, file content, account IDs, passwords or arbitrary paths.
        sys.exit('Recovery archive failed: ' + (str(error) if type(error) is ValueError else type(error).__name__))
