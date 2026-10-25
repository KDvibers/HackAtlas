#!/usr/bin/env python3
"""Restore into a NEW gated database, purge old sessions, then grant runtime access."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

from common import client_command, container_for, database_name, sql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--database", required=True, help="New name beginning with hackatlas_restore_")
    args = parser.parse_args()
    if os.name != "posix":
        raise ValueError("Run inside Linux/WSL with access to the intended Docker daemon.")
    target = database_name(args.database)
    if not re.fullmatch(r"hackatlas_restore_[a-z0-9_]{1,40}", target):
        raise ValueError("Restore target must be a new hackatlas_restore_<unique> database; original DB names are refused.")
    source = args.input.resolve(strict=True)
    checksum = Path(str(source) + ".sha256").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("The required backup SHA256 sidecar is invalid.")
    container = container_for(args.project)
    if sql(container, "postgres", f"SELECT 1 FROM pg_database WHERE datname = '{target}';"):
        raise ValueError("Restore target already exists; no overwrite, DROP or --clean is permitted.")
    started = time.monotonic()
    # Snapshot the archive before database creation so a concurrent file replacement cannot change it.
    with tempfile.TemporaryFile() as snapshot:
        with source.open("rb") as archive:
            shutil.copyfileobj(archive, snapshot)
        snapshot.seek(0)
        actual = hashlib.file_digest(snapshot, "sha256").hexdigest()
        if actual != checksum:
            raise ValueError("Backup checksum mismatch; no database was created.")
        snapshot.seek(0)
        listed = subprocess.run(
            ["docker", "exec", "-i", "--user", "999:999", container, "pg_restore", "--list"],
            stdin=snapshot, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=120,
        )
        if listed.returncode:
            raise ValueError("Input is not a readable PostgreSQL custom archive; no database was created.")
        # CREATE is atomic and still rejects a concurrent creator. Connection limit gates the initial ACL setup.
        sql(container, "postgres", f'CREATE DATABASE "{target}" OWNER hackatlas_migrator TEMPLATE template0 CONNECTION LIMIT 0;\n'
            f'REVOKE ALL ON DATABASE "{target}" FROM PUBLIC;\n'
            f'GRANT CONNECT ON DATABASE "{target}" TO hackatlas_migrator;\n'
            f'ALTER DATABASE "{target}" CONNECTION LIMIT -1;')
        sql(container, target, "REVOKE ALL ON SCHEMA public FROM PUBLIC;\n"
            "GRANT USAGE, CREATE ON SCHEMA public TO hackatlas_migrator;")
        snapshot.seek(0)
        restored = subprocess.run(
            client_command(container, "pg_restore", "migrator", ["--exit-on-error", "--no-owner", "--no-acl",
                           "--role=hackatlas_migrator", "--dbname=" + target]),
            stdin=snapshot, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=1800,
        )
        if restored.returncode:
            raise RuntimeError("pg_restore failed. The new target is retained for diagnosis with runtime CONNECT still denied; source DB is untouched.")
    grants = (Path(__file__).resolve().parents[1] / "database/flyway/afterMigrate.sql").read_text(encoding="utf-8")
    revoked = sql(container, target,
                  "BEGIN;\nSELECT count(*) FROM hackatlas.auth_session;\nDELETE FROM hackatlas.auth_session;\n"
                  + grants + f'\nGRANT CONNECT ON DATABASE "{target}" TO hackatlas_runtime;\nCOMMIT;', role="migrator")
    print(json.dumps({"database": target, "sourceArchive": str(source), "sha256": actual,
                      "revokedSessions": int(revoked), "readyForSmoke": True, "requiresPrivacyReview": True,
                      "durationSeconds": round(time.monotonic() - started, 3)}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(f"Restore failed: {error}")
