#!/usr/bin/env python3
"""Prepare an unapproved B4.2 review; never opens or modifies a database."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

from common import container_for, sql
from deletion_history import make_review, validate_export, write_private_json
from restore_review import read_private_review, require, target_database, validate_state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", dest="export_path", type=Path, required=True)
    parser.add_argument("--source-project", required=True)
    parser.add_argument("--source-database", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.output.name.endswith(".restore-review.json"), "Use a private *.restore-review.json path.")
    export = validate_export(read_private_review(args.export_path), args.source_project, args.source_database)
    database = target_database(args.database)
    container = container_for(args.project)
    state = json.loads(sql(container, database,
        "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=current_database();", role="migrator"))
    validate_state(state)
    require(state["project"] == args.project and state["database"] == database, "Target metadata identity mismatch.")
    write_private_json(args.output, make_review(export, state))
    print(json.dumps({"accountCount": export["accountCount"], "approved": False,
                      "requiresOperatorReview": True, "runtimeAccessGranted": False}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, subprocess.SubprocessError) as error:
        sys.exit("Review preparation failed: " + (str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__))
