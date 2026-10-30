"""Purpose-limited deletion exports; no assertion of complete independent history."""

from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re

from restore_review import account_ids, require, timestamp, validate_state

EXPORT_KEYS = {"formatVersion", "sourceProject", "sourceDatabase", "exportedAt", "accountCount",
               "deletedAccountIds", "historyComplete"}
MAX_BYTES = 1024 * 1024


def validate_export(value, source_project, source_database, *, now=None):
    require(isinstance(source_project, str) and re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", source_project),
            "Invalid explicit source project.")
    require(source_database == "hackatlas", "Only the original hackatlas source database can be exported.")
    require(isinstance(value, dict) and set(value) == EXPORT_KEYS, "Export fields are missing or unknown.")
    require(type(value["formatVersion"]) is int and value["formatVersion"] == 1, "Unsupported export version.")
    require(value["sourceProject"] == source_project and value["sourceDatabase"] == source_database,
            "Export does not match the explicit source identity.")
    require(value["historyComplete"] is False, "An export cannot attest complete independent history.")
    identifiers = account_ids(value["deletedAccountIds"])
    require(identifiers == sorted(identifiers), "Export account UUIDs must be sorted.")
    require(type(value["accountCount"]) is int and value["accountCount"] == len(identifiers),
            "Export account count does not match its UUID list.")
    current = now or datetime.now(timezone.utc)
    require(timestamp(value["exportedAt"]) <= current + timedelta(minutes=5), "Export time is in the future.")
    return {**value, "deletedAccountIds": identifiers}


def make_review(export, target_state):
    validate_state(target_state)
    validated = validate_export(export, export.get("sourceProject"), export.get("sourceDatabase"))
    review = {key: target_state[key] for key in ("formatVersion", "project", "database", "backupSha256", "restoreId")}
    review.update(sourceWritesStopped=False, deletionsReviewed=False, reviewedThrough=None,
                  deletedAccountIds=list(validated["deletedAccountIds"]))
    return review


def write_private_json(path, value):
    require(os.name == "posix", "Run inside Linux/WSL with private filesystem permissions.")
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    require(len(data) <= MAX_BYTES, "Private JSON exceeds 1 MiB; no truncation is permitted.")
    destination = Path(path).absolute()
    require(destination.parent.is_dir() and destination.parent.stat().st_mode & 0o077 == 0,
            "Create an explicit private parent directory (mode 0700 or stricter).")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    directory = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
