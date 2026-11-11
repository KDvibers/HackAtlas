"""Pin the verified rebuild of restic 0.19.1 for Linux amd64."""

import hashlib
import os
from pathlib import Path
import platform
import stat

from restore_review import require

VERSION = '0.19.1'
GO_VERSION = '1.27.4'
DEFAULT_BINARY = Path('backend/target/restic-0.19.1-rebuilt')
BINARY_SIZE = 42897568
BINARY_SHA256 = 'bb5ed103331524cf8a9f2a735456f250a5373be1635e7fcc5ac5c77125253ce6'


def verify_build(binary):
    require(len(binary) == BINARY_SIZE and hashlib.sha256(binary).hexdigest() == BINARY_SHA256,
            'Restic build checksum mismatch.')


def verify_binary(value):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'This pin supports Linux amd64 only.')
    path = Path(value).absolute()
    require(path == path.resolve(), 'Restic binary must not be reached through a symlink.')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as source:
        attributes = os.fstat(source.fileno())
        require(stat.S_ISREG(attributes.st_mode) and attributes.st_size == BINARY_SIZE,
                'Unexpected restic binary file.')
        require(hashlib.file_digest(source, 'sha256').hexdigest() == BINARY_SHA256,
                'Restic binary does not match the approved pin.')
    return path
