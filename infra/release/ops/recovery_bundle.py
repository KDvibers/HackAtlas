"""Strict local recovery files; hashes are integrity checks, not deletion attestations."""

from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import re
import stat

from deletion_history import validate_export, write_private_json
from restore_review import read_private_review, require, timestamp

LIMITS = {'manifest.json': 16 * 1024, 'source.deletion-export.json': 1024 * 1024,
          'database.dump': 1024 * 1024 * 1024, 'database.dump.sha256': 65}
MANIFEST_KEYS = {'formatVersion', 'kind', 'sourceProject', 'sourceDatabase', 'createdAt',
                 'historyComplete', 'files'}


def local_path(value):
    require(os.name == 'posix', 'Run inside Linux/WSL with native private filesystem permissions.')
    path = Path(value).absolute()
    require(path == path.resolve(), 'Symlink or non-canonical paths are refused.')
    return path


def private_directory(value):
    path = local_path(value)
    attributes = path.stat()
    require(stat.S_ISDIR(attributes.st_mode) and attributes.st_mode & 0o077 == 0
            and attributes.st_uid == os.getuid(), 'Directory must be owned by this operator and private (0700).')
    return path


@contextmanager
def private_file(value, maximum):
    path = local_path(value)
    private_directory(path.parent)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as source:
        attributes = os.fstat(source.fileno())
        require(stat.S_ISREG(attributes.st_mode) and attributes.st_mode & 0o077 == 0
                and attributes.st_uid == os.getuid() and attributes.st_nlink == 1,
                'Input must be an owned private regular file, without hardlinks.')
        require(0 < attributes.st_size <= maximum, 'File size is outside the permitted local limit.')
        yield source


def identity(project, database):
    require(isinstance(project, str) and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', project),
            'Invalid explicit source project.')
    require(database == 'hackatlas', 'Only the original hackatlas source database is supported.')


def metadata(path, maximum):
    digest, size = hashlib.sha256(), 0
    with private_file(path, maximum) as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            size += len(block)
            require(size <= maximum, 'File grew beyond its permitted limit.')
            digest.update(block)
    return {'size': size, 'sha256': digest.hexdigest()}


def copy_private(source_path, destination, maximum):
    with private_file(source_path, maximum) as source:
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            count = 0
            for block in iter(lambda: source.read(1024 * 1024), b''):
                count += len(block)
                require(count <= maximum, 'Input grew beyond its permitted limit.')
                output.write(block)
            output.flush()
            os.fsync(output.fileno())


def validate_manifest(value, project, database):
    identity(project, database)
    require(isinstance(value, dict) and set(value) == MANIFEST_KEYS, 'Unknown or missing manifest fields.')
    require(type(value['formatVersion']) is int and value['formatVersion'] == 1, 'Unsupported manifest version.')
    require(value['sourceProject'] == project and value['sourceDatabase'] == database, 'Manifest source identity mismatch.')
    require(value['historyComplete'] is False, 'An archive cannot attest complete deletion history.')
    require(value['kind'] in ('backup', 'deletions'), 'Unsupported bundle kind.')
    require(timestamp(value['createdAt']) <= datetime.now(timezone.utc) + timedelta(minutes=5),
            'Manifest timestamp is in the future.')
    names = {'source.deletion-export.json'}
    if value['kind'] == 'backup':
        names.update(('database.dump', 'database.dump.sha256'))
    require(isinstance(value['files'], dict) and set(value['files']) == names, 'Bundle file allowlist mismatch.')
    for name, attributes in value['files'].items():
        require(isinstance(attributes, dict) and set(attributes) == {'size', 'sha256'}, 'Invalid file metadata.')
        require(type(attributes['size']) is int and 0 < attributes['size'] <= LIMITS[name], 'Invalid file size.')
        require(isinstance(attributes['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', attributes['sha256']),
                'Invalid file digest.')
    return value


def validate_payload(directory, project, database, names):
    export = directory / 'source.deletion-export.json'
    metadata(export, LIMITS[export.name])
    validate_export(read_private_review(export), project, database)
    if 'database.dump' in names:
        backup = directory / 'database.dump'
        with private_file(backup, LIMITS[backup.name]) as source:
            require(source.read(5) == b'PGDMP', 'Backup must be a PostgreSQL custom-format dump.')
        with private_file(directory / 'database.dump.sha256', 65) as source:
            checksum = source.read(66)
        require(re.fullmatch(b'[0-9a-f]{64}\n', checksum), 'Invalid backup checksum sidecar.')
        require(metadata(backup, LIMITS[backup.name])['sha256'] == checksum[:64].decode('ascii'),
                'Backup checksum mismatch.')


def validate_bundle(directory, project, database):
    directory = private_directory(directory)
    manifest_path = directory / 'manifest.json'
    metadata(manifest_path, LIMITS[manifest_path.name])
    manifest = validate_manifest(read_private_review(manifest_path), project, database)
    require({p.name for p in directory.iterdir()} == {'manifest.json', *manifest['files']},
            'Bundle contains missing or unexpected files.')
    for name, expected in manifest['files'].items():
        require(metadata(directory / name, LIMITS[name]) == expected, 'Bundle file digest or size mismatch.')
    validate_payload(directory, project, database, manifest['files'])
    return manifest


def stage_bundle(directory, project, database, export, backup=None):
    directory = private_directory(directory)
    identity(project, database)
    require(not any(directory.iterdir()), 'Staging directory must be empty; no overwrite is permitted.')
    inputs = {'source.deletion-export.json': export}
    if backup is not None:
        inputs.update({'database.dump': backup, 'database.dump.sha256': str(backup) + '.sha256'})
    for name, path in inputs.items():
        copy_private(path, directory / name, LIMITS[name])
    validate_payload(directory, project, database, inputs)
    manifest = {'formatVersion': 1, 'kind': 'backup' if backup is not None else 'deletions',
                'sourceProject': project, 'sourceDatabase': database,
                'createdAt': datetime.now(timezone.utc).isoformat(), 'historyComplete': False,
                'files': {name: metadata(directory / name, LIMITS[name]) for name in inputs}}
    write_private_json(directory / 'manifest.json', manifest)
    return validate_bundle(directory, project, database)
