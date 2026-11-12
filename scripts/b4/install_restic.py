#!/usr/bin/env python3
"""Build the pinned restic binary and install it without overwriting files."""

import argparse
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/release/ops'))
from restic_tool import DEFAULT_BINARY, VERSION, verify_binary, verify_build
from restore_review import require


BUILDER = 'golang:1.27.4-alpine3.24@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf'
LOCKFILES = ROOT / 'scripts/b4/restic-build'


def build_binary():
    result = subprocess.run(
        ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--cap-drop', 'ALL',
         '--security-opt', 'no-new-privileges', '--user', f'{os.getuid()}:{os.getgid()}',
         '--env', 'GOPATH=/tmp/gopath', '--env', 'GOCACHE=/tmp/go-cache',
         '--env', 'GOTOOLCHAIN=local', '--env', 'GOWORK=off', '--env', 'CGO_ENABLED=0',
         '--env', 'GOPROXY=https://proxy.golang.org', '--env', 'GOSUMDB=sum.golang.org',
         '--mount', f'type=bind,source={LOCKFILES},target=/src,readonly', '--workdir', '/src',
         '--entrypoint', '/bin/sh', BUILDER, '-ec',
         'go build -mod=readonly -trimpath -buildvcs=false -ldflags="-w -s" '
         '-o /tmp/restic github.com/restic/restic/cmd/restic >&2\n'
         'go mod verify >&2\n'
         'exec cat /tmp/restic'],
        capture_output=True, timeout=600,
    )
    if result.returncode:
        diagnostic = result.stderr[-6000:].decode(errors='replace').strip()
        raise RuntimeError(f'Pinned restic build failed (exit {result.returncode}).\n{diagnostic}')
    return result.stdout


def install(output):
    path = output.absolute()
    require(path == path.resolve(), 'Output must not use symlinks.')
    if path.exists():
        verify_binary(path)
        return False
    require(path.parent.is_dir(), 'Create the tool directory first.')
    binary = build_binary()
    verify_build(binary)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.restic-', delete=False) as staging:
        candidate = Path(staging.name)
        try:
            staging.write(binary)
            os.fchmod(staging.fileno(), 0o755)
            staging.flush()
            os.fsync(staging.fileno())
            try:
                os.link(candidate, path)
            except FileExistsError:
                verify_binary(path)
                return False
        finally:
            candidate.unlink()
    verify_binary(path)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / DEFAULT_BINARY)
    args = parser.parse_args()
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Use Linux amd64/WSL.')
    if install(args.output):
        print('PASS installed restic ' + VERSION + ' rebuild. Binary SHA256 verified.')
    else:
        print('PASS existing restic ' + VERSION + ' rebuild matches the pinned binary.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit('Restic installation failed: ' + str(error))
