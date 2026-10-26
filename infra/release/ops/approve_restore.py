#!/usr/bin/env python3
"""Apply an explicit private operator review to a NEW quarantined restore only."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys

from common import container_for, sql
from restore_review import approval_sql, read_private_review, require, target_database, validate_review


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--review", type=Path, required=True)
    args = parser.parse_args()
    require(os.name == "posix", "Run inside Linux/WSL with access to the intended Docker daemon.")
    database = target_database(args.database)
    container = container_for(args.project)
    state = json.loads(sql(container, database,
        "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database();",
        role="migrator"))
    require(isinstance(state, dict) and state.get("project") == args.project and state.get("database") == database,
            "Target metadata does not match the explicit project/database.")
    review = read_private_review(args.review)
    ids = validate_review(review, state, now=datetime.now(timezone.utc))
    approved = json.loads(sql(container, database,
        approval_sql(database, state, ids, review["reviewedThrough"]), role="migrator"))
    print(json.dumps({"project": args.project, "database": database, "restoreId": state["restoreId"],
                      "backupSha256": state["backupSha256"], "state": approved["state"],
                      "reviewedAccountIds": approved["reviewedAccountIds"], "deletedAccounts": approved["deletedAccounts"],
                      "operatorReviewAccepted": True, "runtimeAccessGranted": True, "readyForSmoke": True,
                      "publicReleaseReady": False}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        # Review contents, UUIDs and raw SQL never enter command output.
        print("Restore approval failed: " + (str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__),
              file=sys.stderr)
        sys.exit(1)
