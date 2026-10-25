#!/usr/bin/env python3
"""Verify the packaged B2 API on disposable PostgreSQL, then run its Bruno flow."""

import argparse
from contextlib import contextmanager
import importlib.util
import os
from pathlib import Path
import re
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
COLLECTION = ROOT / "tools/bruno-b2"
spec = importlib.util.spec_from_file_location("b1_checks", ROOT / "scripts/b1/run_contract_checks.py")
b1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b1)
OPERATIONS = {
    ("post", "/auth/register"), ("post", "/auth/login"), ("post", "/auth/logout"),
    ("get", "/me"), ("patch", "/me"), ("get", "/me/saved"),
    ("put", "/me/saved/{id}"), ("delete", "/me/saved/{id}"),
    ("get", "/me/applications"), ("put", "/me/applications/{id}"),
    ("delete", "/me/applications/{id}"),
}


def verify_scope(api):
    operations = {
        (method, path) for path, item in api["paths"].items()
        for method, operation in item.items()
        if isinstance(operation, dict) and operation.get("x-phase") == "B2"
    }
    if operations != OPERATIONS:
        raise ValueError("B2 runner requires exactly the eleven approved B2 operations.")


def smoke(api, base_url, session):
    """Exercise state transitions without persisting credentials or response bodies."""
    coverage = set()
    count = 0

    def request(method, operation_path, status=200, *, token=None, path=None, body=None, query=None):
        nonlocal count
        headers = {"Authorization": "Bearer " + token} if token else {}
        response = session.request(
            method, base_url + (path or operation_path), headers=headers, json=body,
            params=query, timeout=10, allow_redirects=False,
        )
        label = f"{method.upper()} {operation_path}"
        if response.status_code != status:
            raise ValueError(f"{label}: expected {status}, received {response.status_code}")
        declared = api["paths"][operation_path][method]["responses"][str(status)]
        if response.headers.get("Set-Cookie"):
            raise ValueError(f"{label}: native API must not create a cookie session.")
        if not response.headers.get("X-Request-Id"):
            raise ValueError(f"{label}: missing server request ID.")
        if status == 204:
            if response.content:
                raise ValueError(f"{label}: 204 must have no body.")
            result = None
        else:
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower().strip()
            if content_type not in declared["content"]:
                raise ValueError(f"{label}: unexpected response media type.")
            result = response.json()
            schema = {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                **declared["content"][content_type]["schema"], "components": api["components"],
            }
            # Never print jsonschema's full ValidationError; it can include an issued token.
            errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(result))
            if errors:
                raise ValueError(f"{label}: response violates canonical schema ({errors[0].validator}).")
            if status >= 400 and (result["status"] != status
                                     or result["requestId"] != response.headers["X-Request-Id"]):
                raise ValueError(f"{label}: invalid Problem status/request ID.")
            if operation_path in {"/auth/register", "/auth/login"} and status < 300:
                if "no-store" not in response.headers.get("Cache-Control", "").lower():
                    raise ValueError("Token responses must be Cache-Control: no-store.")
                if result["tokenType"] != "Bearer" or len(result["accessToken"]) < 32:
                    raise ValueError("Invalid opaque Bearer session response.")
        coverage.add((method, operation_path))
        count += 1
        return result

    def check(condition, message):
        if not condition:
            raise ValueError(message)

    suffix = uuid.uuid4().hex
    password = " " + secrets.token_urlsafe(30) + " "
    email_a, email_b = f"b2-a-{suffix}@example.com", f"b2-b-{suffix}@example.com"
    register = lambda email, name: request("post", "/auth/register", 201, body={
        "email": email, "password": password, "displayName": name,
    })
    a = register(email_a, "B2 проверка A")
    b = register(email_b, "B2 проверка B")
    ta, tb = a["accessToken"], b["accessToken"]
    request("get", "/me", 401)
    request("post", "/auth/login", 401, body={"email": email_a, "password": password.strip()})
    login = request("post", "/auth/login", body={"email": email_a, "password": password})
    check(login["user"]["id"] == a["user"]["id"], "Login must retain the account identity.")
    check(login["accessToken"] != ta, "Login must issue a distinct session.")
    request("post", "/auth/logout", 204, token=login["accessToken"])
    request("get", "/me", 401, token=login["accessToken"])
    profile = request("patch", "/me", token=ta, body={
        "city": "Астана", "countryCode": "KZ", "interests": ["java", "ai-ml"], "roleLabel": "Backend",
    })
    check(profile["stats"]["interestsCount"] == 2, "Profile interest count is incorrect.")
    profile = request("patch", "/me", token=ta, body={"city": None})
    check(profile["city"] is None and profile["countryCode"] == "KZ", "PATCH absent/null semantics failed.")
    request("patch", "/me", 400, token=ta, body={"email": email_b})
    check(request("get", "/me", token=tb)["interests"] == [], "Profile data crossed account boundaries.")
    event_id = b1.fixture("hackathon-detail.json")["id"]
    saved_path, applied_path = "/me/saved/" + event_id, "/me/applications/" + event_id
    for _ in range(2):
        request("put", "/me/saved/{id}", 204, token=ta, path=saved_path)
    # A failed guest-import item must not erase the previous successful item.
    request("put", "/me/saved/{id}", 404, token=ta,
            path="/me/saved/00000000-0000-0000-0000-000000000001")
    page = request("get", "/me/saved", token=ta)
    check(page["totalElements"] == 1 and page["items"][0]["id"] == event_id,
          "Repeated save/partial guest import did not preserve one bookmark.")
    check(request("get", "/me/saved", token=tb)["items"] == [], "Bookmarks crossed account boundaries.")
    request("get", "/me/saved", 400, token=ta, query={"size": 0})
    mark = request("put", "/me/applications/{id}", token=ta, path=applied_path)
    repeated = request("put", "/me/applications/{id}", token=ta, path=applied_path)
    check(mark == repeated and mark["selfReported"] is True, "Repeated application PUT changed the mark.")
    check(request("get", "/me/applications", token=ta)["items"] == [mark], "Application page mismatch.")
    check(request("get", "/me/applications", token=tb)["items"] == [], "Application marks crossed accounts.")
    for _ in range(2):
        request("delete", "/me/saved/{id}", 204, token=ta, path=saved_path)
    check(request("get", "/me/applications", token=ta)["totalElements"] == 1,
          "Removing a bookmark must not remove an application mark.")
    for _ in range(2):
        request("delete", "/me/applications/{id}", 204, token=ta, path=applied_path)
    check(request("get", "/me", token=ta)["stats"] == {
        "savedCount": 0, "appliedCount": 0, "interestsCount": 2,
    }, "Profile statistics must reflect personal data.")
    request("post", "/auth/logout", 204, token=ta)
    request("get", "/me", 401, token=ta)
    request("post", "/auth/logout", 204, token=tb)
    check(coverage == OPERATIONS, "Not all eleven B2 operations were exercised.")
    print(f"PASS {count} B2 HTTP cases: 11 operations, canonical schemas, two-account isolation and revocation.", flush=True)


@contextmanager
def isolated_server(jar, env):
    owner = uuid.uuid4().hex
    name = "hackatlas-b2-" + owner
    java = None
    password = secrets.token_hex(32)
    try:
        b1.docker("create", "--name", name, "--label", "hackatlas.b2-run=" + owner,
                  "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data:rw,size=512m",
                  "--env", "POSTGRES_DB=hackatlas", "--env", "POSTGRES_USER=hackatlas_b2",
                  "--env", "POSTGRES_PASSWORD", b1.POSTGRES_IMAGE,
                  env={**env, "POSTGRES_PASSWORD": password})
        b1.docker("start", name)
        db_port = int(b1.docker("port", name, "5432/tcp").rsplit(":", 1)[1])
        for _ in range(60):
            ready = subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1",
                                    "-U", "hackatlas_b2", "-d", "hackatlas"], capture_output=True, timeout=10)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("The isolated PostgreSQL did not become ready.")
        with tempfile.TemporaryDirectory(prefix="hackatlas-b2-") as temporary:
            work = Path(temporary)
            app_work = work / "app"
            app_work.mkdir()
            source_stat = jar.stat()
            snapshot = app_work / "backend.jar"
            shutil.copyfile(jar, snapshot)
            copied_stat = jar.stat()
            if (source_stat.st_size, source_stat.st_mtime_ns) != (copied_stat.st_size, copied_stat.st_mtime_ns):
                raise ValueError("The source JAR changed while being copied; finish the build and retry.")
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                app_port = sock.getsockname()[1]
            app_env = {**env, "HACKATLAS_DB_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/hackatlas",
                       "HACKATLAS_DB_USERNAME": "hackatlas_b2", "HACKATLAS_DB_PASSWORD": password}
            with (work / "application.log").open("w", encoding="utf-8") as log:
                java = subprocess.Popen(
                    ["java", "-jar", str(snapshot), "--spring.profiles.active=dev",
                     "--hackatlas.demo.enabled=true", "--hackatlas.demo.as-of=2026-10-12T10:00:00Z",
                     "--hackatlas.auth.registration-enabled=true",
                     "--server.address=127.0.0.1", f"--server.port={app_port}"],
                    cwd=app_work, env=app_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                )
                with requests.Session() as session:
                    session.trust_env = False
                    for _ in range(90):
                        if java.poll() is not None:
                            raise RuntimeError("B2 JAR exited before readiness; inspect startup separately.")
                        try:
                            response = session.get(f"http://127.0.0.1:{app_port}/actuator/health/readiness",
                                                   timeout=1, allow_redirects=False)
                            if response.status_code == 200 and response.json().get("status") == "UP":
                                break
                        except (requests.RequestException, ValueError):
                            pass
                        time.sleep(1)
                    else:
                        raise RuntimeError("B2 JAR did not become ready within 90 seconds.")
                    yield f"http://127.0.0.1:{app_port}/api/v1", session
                b1.stop_process(java)
    finally:
        b1.stop_process(java)
        inspected = subprocess.run(
            ["docker", "inspect", "--format", '{{index .Config.Labels "hackatlas.b2-run"}}', name],
            capture_output=True, text=True, timeout=30,
        )
        if inspected.returncode == 0:
            if inspected.stdout.strip() != owner:
                raise RuntimeError("Refusing to remove a container without this run's ownership label.")
            b1.docker("rm", "--force", name)
            print("CLEANUP removed only this run's PostgreSQL container.", flush=True)
        elif "No such" not in inspected.stderr:
            raise RuntimeError(f"Could not confirm cleanup of {name}; inspect this exact name manually.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--node", default="node")
    parser.add_argument("--bruno-cli", type=Path,
                        default=ROOT / "tools/bruno/node_modules/@usebruno/cli/bin/bru.js")
    args = parser.parse_args()
    if os.name != "posix":
        raise ValueError("Run inside Linux/WSL with JVM and Docker on the same host.")
    jar, cli = args.jar.resolve(strict=True), args.bruno_cli.resolve(strict=True)
    env = b1.clean_environment()
    env.pop("NODE_OPTIONS", None)
    env.pop("NODE_PATH", None)
    version = b1.run([args.node, str(cli), "--version"], env=env, capture_output=True, text=True)
    if version.stdout.strip() != "4.2.1":
        raise ValueError("Install pinned Bruno CLI 4.2.1 using npm ci in tools/bruno.")
    api = yaml.safe_load(b1.SCHEMA.read_text(encoding="utf-8"))
    verify_scope(api)
    b1.docker("version", "--format", "{{.Server.Version}}")
    with isolated_server(jar, env) as (base_url, session):
        smoke(api, base_url, session)
        # Process environment carries only synthetic credentials; never CLI arguments or .env files.
        suffix = uuid.uuid4().hex
        bruno_env = {**env, "HACKATLAS_B2_PASSWORD": secrets.token_urlsafe(36),
                     "HACKATLAS_B2_EMAIL_A": f"bruno-a-{suffix}@example.com",
                     "HACKATLAS_B2_EMAIL_B": f"bruno-b-{suffix}@example.com"}
        with tempfile.TemporaryDirectory(prefix="hackatlas-b2-bruno-") as temporary:
            # Do not read a developer's local .env, report files or global collection overrides.
            isolated_collection = Path(temporary) / "collection"
            shutil.copytree(COLLECTION, isolated_collection,
                            ignore=shutil.ignore_patterns(".env", ".env.*", "results", ".git"))
            result = subprocess.run(
                [args.node, str(cli), "run", "--env", "local", "--noproxy",
                 "--env-var", "baseUrl=" + base_url], cwd=isolated_collection, env=bruno_env,
                capture_output=True, text=True, timeout=300,
            )
            output = result.stdout + result.stderr
            output = output.replace(bruno_env["HACKATLAS_B2_PASSWORD"], "[REDACTED]")
            output = re.sub(r"(?i)(Bearer\s+)[A-Za-z0-9._~+/-]{32,}=*", r"\1[REDACTED]", output)
            output = re.sub(r'(?i)(["\']?accessToken["\']?\s*[:=]\s*["\']?)[A-Za-z0-9_-]{32,}',
                            r"\1[REDACTED]", output)
            print(output, end="", flush=True)
            if result.returncode:
                raise RuntimeError(f"Bruno B2 exited with status {result.returncode}.")
    print("PASS B2 packaged API and Bruno; ephemeral accounts and database removed.", flush=True)
    return 0


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, b1.interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit("B2 verification interrupted; owned-resource cleanup attempted.")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        # Do not print HTTP bodies, process environments or credentials.
        sys.exit(f"B2 verification failed: {error}")
