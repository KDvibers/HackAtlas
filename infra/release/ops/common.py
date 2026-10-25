"""Local Docker/PostgreSQL helpers. No Compose .env loading or credential output."""

import re
import subprocess


def container_for(project):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise ValueError("Invalid explicit Compose project name.")
    result = subprocess.run([
        "docker", "ps", "--filter", "label=com.docker.compose.project=" + project,
        "--filter", "label=com.docker.compose.service=postgres", "--filter", "label=hackatlas.release=true",
        "--format", "{{.ID}}",
    ], check=True, capture_output=True, text=True, timeout=30)
    containers = result.stdout.split()
    if len(containers) != 1:
        raise ValueError("Expected exactly one running release PostgreSQL in the named project.")
    return containers[0]


def database_name(value):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
        raise ValueError("Invalid database identifier.")
    return value


def client_command(container, tool, role="admin", arguments=()):
    if role not in {"admin", "migrator"} or tool not in {"psql", "pg_dump", "pg_restore"}:
        raise ValueError("Unsupported operator client/role.")
    secret = "postgres-admin-password" if role == "admin" else "migrator-password"
    # Password is read inside the container, never a Docker environment entry or CLI argument.
    shell = 'PGPASSWORD=$(cat /run/secrets/' + secret + '); export PGPASSWORD; exec "$@"'
    return ["docker", "exec", "-i", "--user", "999:999", container, "/bin/sh", "-ec", shell,
            "release-db-client", tool, "--host=127.0.0.1", "--username=hackatlas_" + role, *arguments]


def sql(container, database, statements, role="admin"):
    result = subprocess.run(
        client_command(container, "psql", role, ["-X", "-qAt", "--set=ON_ERROR_STOP=1",
                       "--set=VERBOSITY=sqlstate", "--dbname=" + database_name(database)]),
        input=statements, text=True, capture_output=True, timeout=120,
    )
    if result.returncode:
        # SQL errors can contain data or credentials. Do not forward raw stderr.
        raise RuntimeError(f"PostgreSQL operator statement failed (exit {result.returncode}); no raw SQL/data logged.")
    return result.stdout.strip()
