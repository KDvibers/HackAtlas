"""Official restic 0.19.1 Linux amd64 pin; no PATH lookup or automatic upgrades."""

import bz2
import hashlib
import os
from pathlib import Path
import platform
import stat

from restore_review import require

VERSION = '0.19.1'
ASSET = 'restic_0.19.1_linux_amd64.bz2'
URL = 'https://github.com/restic/restic/releases/download/v0.19.1/' + ASSET
ARCHIVE_SHA256 = 'f415415624dcc452f2a02b8c33641791a8c6d6d3b65bbb3543fcf9a25151585c'
BINARY_SHA256 = '20d4142678d0d95ec11a4759def1b73fd9190abc9ca19e4b62d067c0b387e639'


def verify_download(raw):
    require(hashlib.sha256(raw).hexdigest() == ARCHIVE_SHA256, 'Restic download checksum mismatch.')
    binary = bz2.decompress(raw)
    require(hashlib.sha256(binary).hexdigest() == BINARY_SHA256, 'Restic binary checksum mismatch.')
    return binary


def verify_binary(value):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'This pin supports Linux amd64 only.')
    path = Path(value).absolute()
    require(path == path.resolve(), 'Restic binary must not be reached through a symlink.')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as source:
        attributes = os.fstat(source.fileno())
        require(stat.S_ISREG(attributes.st_mode) and attributes.st_size == 30941346,
                'Unexpected restic binary file.')
        require(hashlib.file_digest(source, 'sha256').hexdigest() == BINARY_SHA256,
                'Restic binary does not match the approved pin.')
    return path
