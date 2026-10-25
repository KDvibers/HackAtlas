#!/usr/bin/env python3
"""Check a built B1 JAR against the canonical contract on an isolated local database."""

import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid

import requests
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "api/openapi.yaml"
COLLECTION = ROOT / "tools/bruno"
POSTGRES_IMAGE = (
    "postgres:17.11-bookworm@sha256:"
    "858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27"
)
OPERATIONS = {
    ("get", "/hackathons"),
    ("get", "/hackathons/{id}"),
    ("get", "/metadata/filters"),
}
CHECKS = ",".join((
    "not_a_server_error", "status_code_conformance", "content_type_conformance",
    "response_headers_conformance", "response_schema_conformance", "negative_data_rejection",
))


def run(command, **kwargs):
    return subprocess.run(command, check=True, timeout=300, **kwargs)


def docker(*arguments, **kwargs):
    return run(["docker", *arguments], capture_output=True, text=True, **kwargs).stdout.strip()


def fixture(name):
    return json.loads((ROOT / "api/examples" / name).read_text(encoding="utf-8"))


def verify_scope(api):
    operations = {
        (method, path)
        for path, item in api["paths"].items()
        for method, operation in item.items()
        if isinstance(operation, dict) and operation.get("x-phase") == "B1"
    }
    if operations != OPERATIONS:
        raise ValueError("B1 must contain exactly the three approved GET operations.")
    for method, path in operations:
        if api["paths"][path][method].get("security") != []:
            raise ValueError("B1 runner only accepts public operations.")


def smoke(api, base_url, session):
    """Use real responses; fixtures are comparison data, never a server substitute."""
    def get(operation_path, expected_status=200, *, path=None, query=None):
        response = session.get(
            base_url + (path or operation_path), params=query, timeout=10, allow_redirects=False,
        )
        if response.status_code != expected_status:
            raise ValueError(f"GET {operation_path}: expected {expected_status}, got {response.status_code}")
        content = api["paths"][operation_path]["get"]["responses"][str(expected_status)]["content"]
        media_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if media_type not in content:
            raise ValueError(f"GET {operation_path}: unexpected content type {media_type}")
        body = response.json()
        schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            **content[media_type]["schema"], "components": api["components"],
        }
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(body)
        if expected_status >= 400:
            if body["status"] != expected_status or not body["requestId"].strip():
                raise ValueError("Problem must carry the real status and a nonempty requestId.")
        return body

    page = get("/hackathons", query={"sort": "NEWEST"})
    if page != fixture("catalog-page.json"):
        raise ValueError("NEWEST DEMO page differs from canonical fixture at the fixed clock.")
    empty = get("/hackathons", query={"query": "__hackatlas_b1_no_matching_event__"})
    if empty != fixture("catalog-empty.json"):
        raise ValueError("Empty result differs from canonical fixture at the fixed clock.")
    detail_fixture = fixture("hackathon-detail.json")
    detail = get("/hackathons/{id}", path="/hackathons/" + detail_fixture["id"])
    if detail != detail_fixture:
        raise ValueError("AI detail differs from canonical DEMO fixture.")
    metadata = get("/metadata/filters")
    expected_metadata = fixture("filter-metadata.json")
    # The shared fixture lists all enum formats; the real catalogue exposes only available ones.
    expected_metadata["formats"] = [
        item for item in expected_metadata["formats"] if item["id"] in {"ONLINE", "OFFLINE"}
    ]
    for key, expected in expected_metadata.items():
        normalize = lambda values: sorted(json.dumps(item, sort_keys=True) for item in values)
        if normalize(metadata[key]) != normalize(expected):
            raise ValueError(f"Metadata {key} differs from seeded catalogue/interest values.")
    searched = get("/hackathons", query={"query": " ai ", "format": "ONLINE"})
    if [item["id"] for item in searched["items"]] != [detail_fixture["id"]]:
        raise ValueError("Trimmed case-insensitive search combined with format does not match.")
    for query in (
        {"size": "0"}, {"size": "51"}, {"page": "-1"}, {"sort": "INVALID"},
        {"startsFrom": "2026-12-01", "startsTo": "2026-11-01"},
    ):
        problem = get("/hackathons", 400, query=query)
        if problem["code"] != "VALIDATION_ERROR":
            raise ValueError("Invalid query must produce VALIDATION_ERROR.")
    get("/hackathons/{id}", 400, path="/hackathons/not-a-uuid")
    missing = get(
        "/hackathons/{id}", 404,
        path="/hackathons/00000000-0000-0000-0000-000000000001",
    )
    if missing["code"] != "NOT_FOUND":
        raise ValueError("Unknown UUID must produce NOT_FOUND.")
    print("PASS 12 deterministic HTTP cases: canonical schemas, DEMO baseline, queries and errors.", flush=True)


def clean_environment():
    # Do not inherit application configuration, external proxies, hooks or Java injection options.
    env = {
        key: value for key, value in os.environ.items()
        if not key.upper().startswith(("HACKATLAS_", "SPRING_", "SCHEMATHESIS_", "BRUNO_"))
        and key.upper() not in {
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "PYTHONPATH",
        }
    }
    env.update({"NO_PROXY": "*", "no_proxy": "*"})
    return env


def stop_process(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def remove_owned_container(name, owner):
    result = subprocess.run(
        ["docker", "inspect", "--format", '{{index .Config.Labels "hackatlas.b1-run"}}', name],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode == 0:
        if result.stdout.strip() != owner:
            raise RuntimeError("Refusing to remove a container without this run's ownership label.")
        docker("rm", "--force", name)
        print("CLEANUP removed only this run's PostgreSQL container.", flush=True)
    elif "No such" not in result.stderr:
        raise RuntimeError(f"Could not confirm cleanup of {name}; inspect this exact name manually.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True, help="Already built executable backend JAR")
    parser.add_argument("--node", default="node", help="Linux Node executable for the pinned Bruno CLI")
    parser.add_argument("--bruno-cli", type=Path, default=COLLECTION / "node_modules/@usebruno/cli/bin/bru.js")
    parser.add_argument("--max-examples", type=int, default=100, help="Generated cases per operation")
    parser.add_argument("--seed", type=int, default=20261020)
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    bruno_cli = args.bruno_cli.resolve(strict=True)
    if os.name != "posix":
        raise ValueError("Run inside Linux/WSL with JVM and Docker on the same host.")
    if args.max_examples < 1:
        raise ValueError("--max-examples must be positive.")
    if importlib.metadata.version("schemathesis") != "4.32.2":
        raise ValueError("Install scripts/b1/requirements.txt in the runner's virtual environment.")
    st = Path(sys.executable).parent / "schemathesis"
    if not st.is_file():
        raise ValueError("Schemathesis executable is missing from this virtual environment.")
    api = yaml.safe_load(SCHEMA.read_text(encoding="utf-8"))
    verify_scope(api)
    env = clean_environment()
    docker("version", "--format", "{{.Server.Version}}")
    version = run([args.node, str(bruno_cli), "--version"], env=env, capture_output=True, text=True)
    if version.stdout.strip() != "4.2.1":
        raise ValueError("Run npm ci in tools/bruno to install the pinned Bruno CLI 4.2.1.")
    run([sys.executable, str(ROOT / "scripts/validate_contract.py")], env=env)

    owner = uuid.uuid4().hex
    container = "hackatlas-b1-" + owner
    java = None
    password = secrets.token_hex(32)
    try:
        # tmpfs replaces the image's data volume; there are no shared/persistent DB volumes.
        db_env = {**env, "POSTGRES_PASSWORD": password}
        docker(
            "create", "--name", container, "--label", "hackatlas.b1-run=" + owner,
            "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data:rw,size=512m",
            "--env", "POSTGRES_DB=hackatlas", "--env", "POSTGRES_USER=hackatlas_b1",
            "--env", "POSTGRES_PASSWORD", POSTGRES_IMAGE, env=db_env,
        )
        docker("start", container)
        db_port = int(docker("port", container, "5432/tcp").rsplit(":", 1)[1])
        for _ in range(60):
            ready = subprocess.run(
                ["docker", "exec", container, "pg_isready", "-h", "127.0.0.1",
                 "-U", "hackatlas_b1", "-d", "hackatlas"],
                capture_output=True, timeout=10,
            )
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("The isolated PostgreSQL did not become ready.")
        with tempfile.TemporaryDirectory(prefix="hackatlas-b1-") as temporary:
            work = Path(temporary)
            app_work = work / "app"
            app_work.mkdir()
            # Windows mounts may lock an executing JAR; run an immutable Linux-local snapshot.
            source_stat = jar.stat()
            snapshot = app_work / "backend.jar"
            shutil.copyfile(jar, snapshot)
            copied_stat = jar.stat()
            if (source_stat.st_size, source_stat.st_mtime_ns) != (
                copied_stat.st_size, copied_stat.st_mtime_ns
            ):
                raise ValueError("The source JAR changed while being copied; finish the build and retry.")
            # ../infra/.env cannot exist in this fresh temporary parent.
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                app_port = sock.getsockname()[1]
            base_url = f"http://127.0.0.1:{app_port}/api/v1"
            app_env = {
                **env, "HACKATLAS_DB_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/hackatlas",
                "HACKATLAS_DB_USERNAME": "hackatlas_b1", "HACKATLAS_DB_PASSWORD": password,
            }
            with (work / "application.log").open("w", encoding="utf-8") as log:
                java = subprocess.Popen(
                    ["java", "-jar", str(snapshot), "--spring.profiles.active=dev",
                     "--hackatlas.demo.enabled=true", "--hackatlas.demo.as-of=2026-10-12T10:00:00Z",
                     "--server.address=127.0.0.1", f"--server.port={app_port}"],
                    cwd=app_work, env=app_env, stdout=log, stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
                with requests.Session() as session:
                    session.trust_env = False
                    for _ in range(90):
                        if java.poll() is not None:
                            raise RuntimeError("B1 JAR exited before readiness; run verify and inspect startup separately.")
                        try:
                            response = session.get(
                                f"http://127.0.0.1:{app_port}/actuator/health/readiness",
                                timeout=1, allow_redirects=False,
                            )
                            if response.status_code == 200 and response.json().get("status") == "UP":
                                break
                        except (requests.RequestException, ValueError):
                            pass
                        time.sleep(1)
                    else:
                        raise RuntimeError("B1 JAR did not become ready within 90 seconds.")
                    print(f"ISOLATED SERVER {base_url}; only three public B1 GET operations.", flush=True)
                    smoke(api, base_url, session)
                run(
                    [args.node, str(bruno_cli), "run", "--env", "local", "--noproxy",
                     "--env-var", "baseUrl=" + base_url],
                    cwd=COLLECTION, env=env,
                )
                config = work / "schemathesis.toml"
                config.write_text("# Explicit empty config; do not load user/parent hooks.\n", encoding="utf-8")
                run(
                    [str(st), "--no-color", "--config-file", str(config), "run", str(SCHEMA),
                     "--url", base_url, "--include-by", '/x-phase == "B1"',
                     "--phases", "examples,fuzzing", "--checks", CHECKS,
                     "--max-examples", str(args.max_examples), "--seed", str(args.seed),
                     "--workers", "1", "--max-redirects", "0", "--request-timeout", "5",
                     "--generation-database", ":memory:", "--output-sanitize", "true"],
                    cwd=work, env=env,
                )
            stop_process(java)
        print("PASS B1 isolated HTTP, Bruno and Schemathesis checks.", flush=True)
        return 0
    finally:
        stop_process(java)
        remove_owned_container(container, owner)


def interrupted(_signum, _frame):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit("B1 verification interrupted; owned-resource cleanup attempted.")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        # Never print child environment or credentials. Tool failures retain their original exit.
        sys.exit(f"B1 verification failed: {error}")
