#!/usr/bin/env python3
"""Check packaged B3 moderation/deletion flows using disposable accounts and PostgreSQL."""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
COLLECTION = ROOT / "tools/bruno-b3"
spec = importlib.util.spec_from_file_location("b2_checks", ROOT / "scripts/b2/run_contract_checks.py")
b2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b2)
b1 = b2.b1
OPERATIONS = {
    ("post", "/suggestions"), ("post", "/hackathons/{id}/reports"), ("post", "/me/delete-account"),
}


def verify_scope(api):
    operations = {
        (method, path) for path, item in api["paths"].items()
        for method, operation in item.items()
        if isinstance(operation, dict) and operation.get("x-phase") == "B3"
    }
    if operations != OPERATIONS:
        raise ValueError("B3 runner requires exactly the three approved JSON API operations.")
    if any(api["paths"][path][method].get("security") != [{"BearerSession": []}]
           for method, path in operations):
        raise ValueError("Every B3 JSON operation must require BearerSession.")


def smoke(api, base_url, session):
    count = 0
    coverage = set()

    def request(method, operation_path, status=200, *, token=None, path=None, body=None):
        nonlocal count
        response = session.request(
            method, base_url + (path or operation_path), json=body,
            headers={"Authorization": "Bearer " + token} if token else {},
            timeout=10, allow_redirects=False,
        )
        label = f"{method.upper()} {operation_path}"
        if response.status_code != status:
            raise ValueError(f"{label}: expected {status}, received {response.status_code}")
        operation = api["paths"][operation_path][method]
        declared = operation["responses"][str(status)]
        if response.headers.get("Set-Cookie"):
            raise ValueError(f"{label}: native API must not create a cookie session.")
        if not response.headers.get("X-Request-Id"):
            raise ValueError(f"{label}: missing server request ID.")
        if status == 204:
            if response.content:
                raise ValueError(f"{label}: 204 must have no body.")
            result = None
        else:
            media_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if media_type not in declared["content"]:
                raise ValueError(f"{label}: unexpected response media type.")
            result = response.json()
            schema = {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                **declared["content"][media_type]["schema"], "components": api["components"],
            }
            errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(result))
            if errors:
                # ValidationError may contain an auth response; report only its validator, never the body.
                raise ValueError(f"{label}: response violates canonical schema ({errors[0].validator}).")
            if status >= 400 and (result["status"] != status
                                     or result["requestId"] != response.headers["X-Request-Id"]):
                raise ValueError(f"{label}: invalid Problem status/request ID.")
            if "accessToken" in result and "no-store" not in response.headers.get("Cache-Control", "").lower():
                raise ValueError("Token responses must be Cache-Control: no-store.")
        if operation.get("x-phase") == "B3":
            coverage.add((method, operation_path))
        count += 1
        return result

    def check(condition, message):
        if not condition:
            raise ValueError(message)

    suffix = uuid.uuid4().hex
    password = " " + secrets.token_urlsafe(30) + " "
    email_a, email_b = f"b3-a-{suffix}@example.com", f"b3-b-{suffix}@example.com"

    def register(email):
        return request("post", "/auth/register", 201, body={
            "email": email, "password": password, "displayName": "B3 disposable account",
        })

    a, b = register(email_a), register(email_b)
    ta, tb = a["accessToken"], b["accessToken"]
    second = request("post", "/auth/login", body={"email": email_a, "password": password})["accessToken"]
    event_id = b1.fixture("hackathon-detail.json")["id"]
    report_path = "/hackathons/" + event_id + "/reports"
    report = {"reason": "DATES", "message": "DEMO: please verify the published dates."}
    suggestion = {"url": "https://example.com/b3-" + suffix, "comment": "DEMO suggestion for moderation."}
    baseline = request("get", "/hackathons")
    request("put", "/me/saved/{id}", 204, token=ta, path="/me/saved/" + event_id)
    request("put", "/me/applications/{id}", token=ta, path="/me/applications/" + event_id)
    request("patch", "/me", token=ta, body={"interests": ["java"]})
    request("put", "/me/saved/{id}", 204, token=tb, path="/me/saved/" + event_id)
    request("post", "/suggestions", 401, body=suggestion)
    request("post", "/hackathons/{id}/reports", 401, path=report_path, body=report)
    request("post", "/me/delete-account", 401, body={"password": password})
    suggested = request("post", "/suggestions", 202, token=ta, body=suggestion)
    reported = request("post", "/hackathons/{id}/reports", 202, token=ta, path=report_path, body=report)
    check(suggested["status"] == reported["status"] == "PENDING", "Submissions must remain pending moderation.")
    check(suggested["id"] != reported["id"], "Distinct submissions must have distinct receipts.")
    check(request("get", "/hackathons") == baseline, "Submitting a suggestion/report must not publish or edit the catalogue.")
    for url in ("http://example.com/event", "javascript:alert(1)", "https://user@example.com/event"):
        request("post", "/suggestions", 400, token=ta, body={"url": url, "comment": None})
    request("post", "/hackathons/{id}/reports", 404, token=ta,
            path="/hackathons/00000000-0000-0000-0000-000000000001/reports", body=report)
    request("post", "/hackathons/{id}/reports", 400, token=ta,
            path="/hackathons/not-a-uuid/reports", body=report)
    request("post", "/hackathons/{id}/reports", 400, token=ta,
            path=report_path, body={"reason": "UNKNOWN", "message": report["message"]})
    # Password whitespace remains significant for the sensitive deletion operation too.
    request("post", "/me/delete-account", 401, token=ta, body={"password": password.strip()})
    check(request("get", "/me", token=ta)["id"] == a["user"]["id"],
          "An incorrect password must not remove or revoke the account.")
    request("post", "/me/delete-account", 204, token=ta, body={"password": password})
    request("get", "/me", 401, token=ta)
    request("get", "/me", 401, token=second)
    request("post", "/me/delete-account", 401, token=second, body={"password": password})
    request("post", "/suggestions", 401, token=ta, body=suggestion)
    request("post", "/auth/login", 401, body={"email": email_a, "password": password})
    check(request("get", "/me", token=tb)["id"] == b["user"]["id"], "Deleting A must not change account B.")
    check(request("get", "/me/saved", token=tb)["totalElements"] == 1,
          "Deleting A must not remove another account's bookmark.")
    fresh = register(email_a)
    check(fresh["user"]["id"] != a["user"]["id"], "A later registration must get a new identity.")
    check(fresh["user"]["stats"] == {"savedCount": 0, "appliedCount": 0, "interestsCount": 0},
          "A later registration must not inherit deleted personal data.")
    check(request("get", "/me/saved", token=fresh["accessToken"])["items"] == [], "Deleted saved data leaked.")
    check(request("get", "/me/applications", token=fresh["accessToken"])["items"] == [], "Deleted application marks leaked.")
    request("post", "/me/delete-account", 204, token=fresh["accessToken"], body={"password": password})
    request("post", "/me/delete-account", 204, token=tb, body={"password": password})
    request("get", "/me", 401, token=tb)
    check(coverage == OPERATIONS, "Not all three B3 operations were exercised.")
    print(f"PASS {count} B3 HTTP cases: 3 operations, canonical schemas, pending moderation and account deletion.", flush=True)


def run_bruno(node, cli, base_url, env):
    suffix = uuid.uuid4().hex
    bruno_env = {**env, "HACKATLAS_B3_PASSWORD": secrets.token_urlsafe(36),
                 "HACKATLAS_B3_EMAIL_A": f"bruno-b3-a-{suffix}@example.com",
                 "HACKATLAS_B3_EMAIL_B": f"bruno-b3-b-{suffix}@example.com"}
    with tempfile.TemporaryDirectory(prefix="hackatlas-b3-bruno-") as temporary:
        collection = Path(temporary) / "collection"
        shutil.copytree(COLLECTION, collection,
                        ignore=shutil.ignore_patterns(".env", ".env.*", "results", ".git"))
        result = subprocess.run(
            [node, str(cli), "run", "--env", "local", "--noproxy", "--env-var", "baseUrl=" + base_url],
            cwd=collection, env=bruno_env, capture_output=True, text=True, timeout=300,
        )
        output = (result.stdout + result.stderr).replace(bruno_env["HACKATLAS_B3_PASSWORD"], "[REDACTED]")
        output = re.sub(r"(?i)(Bearer\s+)[A-Za-z0-9._~+/-]{32,}=*", r"\1[REDACTED]", output)
        output = re.sub(r'(?i)(["\']?accessToken["\']?\s*[:=]\s*["\']?)[A-Za-z0-9_-]{32,}',
                        r"\1[REDACTED]", output)
        print(output, end="", flush=True)
        if result.returncode:
            raise RuntimeError(f"Bruno B3 exited with status {result.returncode}.")


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
    # Reuse the tested B2 lifecycle. This creates a NEW owned container, never reuses a B2 run's DB.
    # Container prefix/ownership label intentionally remain hackatlas-b2-/hackatlas.b2-run.
    with b2.isolated_server(jar, env) as (base_url, session):
        smoke(api, base_url, session)
        run_bruno(args.node, cli, base_url, env)
    print("PASS B3 packaged API and Bruno; only disposable accounts were deleted and the database removed.", flush=True)
    return 0


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, b1.interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit("B3 verification interrupted; owned-resource cleanup attempted.")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(f"B3 verification failed: {error}")
