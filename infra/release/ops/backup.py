#!/usr/bin/env python3
"""Write a new custom-format backup and SHA256 sidecar; never overwrite an existing file."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from common import client_command, container_for, database_name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--database", default="hackatlas")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "posix":
        raise ValueError("Run inside Linux/WSL so backup permissions are enforced.")
    database = database_name(args.database)
    output = args.output.absolute()
    checksum = Path(str(output) + ".sha256")
    if output.exists() or checksum.exists():
        raise ValueError("Backup or checksum already exists; choose a new output name.")
    if not output.parent.is_dir():
        raise ValueError("Create an explicit private backup directory first.")
    container = container_for(args.project)
    started = time.monotonic()
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as destination:
        result = subprocess.run(
            client_command(container, "pg_dump", arguments=["--format=custom", "--no-owner", "--no-acl",
                           "--dbname=" + database]), stdout=destination, stderr=subprocess.PIPE, timeout=1800,
        )
        destination.flush()
        os.fsync(destination.fileno())
    if result.returncode:
        raise RuntimeError("pg_dump failed; the incomplete new output is retained without a checksum. Source DB was not modified.")
    with output.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    fd = os.open(checksum, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as sidecar:
        sidecar.write(digest + "\n")
        sidecar.flush()
        os.fsync(sidecar.fileno())
    print(json.dumps({"database": database, "output": str(output), "checksum": str(checksum),
                      "bytes": output.stat().st_size, "sha256": digest,
                      "durationSeconds": round(time.monotonic() - started, 3)}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(f"Backup failed: {error}")
