#!/usr/bin/env python3
"""Export all currently committed deletion receipts from an explicit original source."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from common import container_for, sql
from deletion_history import validate_export, write_private_json
from restore_review import require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--database", default="hackatlas", choices=["hackatlas"])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.output.name.endswith(".deletion-export.json"), "Use a private *.deletion-export.json path.")
    container = container_for(args.project)
    result = json.loads(sql(container, args.database, """
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
  'sourceComment', shobj_description(oid, 'pg_database'),
  'exportedAt', clock_timestamp(),
  'deletedAccountIds', (SELECT coalesce(json_agg(account_id ORDER BY account_id), '[]'::json)
    FROM (SELECT account_id FROM hackatlas.account_deletion_receipt ORDER BY account_id LIMIT 10001) r))
FROM pg_database WHERE datname=current_database();
COMMIT;
""", role="migrator"))
    require(result["sourceComment"] is None, "A restored or marked database is not an independent original source.")
    identifiers = result["deletedAccountIds"]
    export = validate_export({"formatVersion": 1, "sourceProject": args.project, "sourceDatabase": args.database,
                              "exportedAt": result["exportedAt"], "accountCount": len(identifiers),
                              "deletedAccountIds": identifiers, "historyComplete": False},
                             args.project, args.database, now=datetime.now(timezone.utc))
    write_private_json(args.output, export)
    print(json.dumps({"accountCount": export["accountCount"], "historyComplete": False,
                      "readyForRestoreApproval": False}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, subprocess.SubprocessError) as error:
        sys.exit("Deletion export failed: " + (str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__))
