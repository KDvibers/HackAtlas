#!/usr/bin/env python3
"""Create three independent DB passwords in a NEW private Linux directory; print no secrets."""

import argparse
import json
import os
from pathlib import Path
import secrets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "posix":
        raise ValueError("Use a native Linux/WSL directory, not a Windows checkout with different permission semantics.")
    directory = args.directory.absolute()
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    if directory.stat().st_mode & 0o077:
        raise ValueError("The filesystem did not preserve private directory permissions.")
    for name in ("postgres-admin-password", "migrator-password", "runtime-password"):
        path = directory / name
        with path.open("x", encoding="ascii") as output:
            output.write(secrets.token_hex(32) + "\n")
        # Parent is 0700; mounted individual files must be readable by PG999 and Java10001.
        path.chmod(0o444)
    print(json.dumps({"directory": str(directory), "created": 3, "valuesPrinted": False}))


if __name__ == "__main__":
    main()
