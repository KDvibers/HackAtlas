#!/usr/bin/env python3
"""Install the pinned official restic locally; never overwrite an existing binary."""

import argparse
import os
from pathlib import Path
import platform
import sys
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'infra/release/ops'))
from restic_tool import URL, VERSION, verify_binary, verify_download
from restore_review import require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Use Linux amd64/WSL.')
    path = args.output.absolute()
    require(path == path.resolve(), 'Output must not use symlinks.')
    if path.exists():
        verify_binary(path)
        print('PASS existing restic ' + VERSION + ' matches pin; not overwritten.')
        return
    require(path.parent.is_dir(), 'Create the tool directory first.')
    with urllib.request.urlopen(URL, timeout=60) as response:
        raw = response.read(16 * 1024 * 1024 + 1)
    binary = verify_download(raw)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o755)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(binary)
        output.flush()
        os.fsync(output.fileno())
    verify_binary(path)
    print('PASS installed restic ' + VERSION + '; compressed and binary SHA256 verified.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as error:
        sys.exit('Restic installation failed: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__))
