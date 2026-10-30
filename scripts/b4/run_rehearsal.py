#!/usr/bin/env python3
"""Rehearse a release locally with disposable Docker resources and explicitly trusted TLS."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import selectors
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

import yaml
from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / "infra/release"
sys.path.insert(0, str(RELEASE / "ops"))
from restore_review import approval_sql
from restic_tool import verify_binary
from recovery_archive_checks import RecoveryArchiveChecks
SERVICES = {"init-volumes", "postgres", "migrate", "app", "proxy"}
SECRET_FILES = ("postgres-admin-password", "migrator-password", "runtime-password")


class RehearsalFailure(RuntimeError):
    pass


def require(condition, label):
    if not condition:
        raise RehearsalFailure(label)


def sql_value(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (list, dict)):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return "'" + str(value).replace("'", "''") + "'"


def fingerprint(rows):
    canonical = "\n".join(sorted(rows)).encode("utf-8")
    return {"rows": len(rows), "sha256": hashlib.sha256(canonical).hexdigest()}


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def validate_bindings(config):
    services = config["services"]
    for name, service in services.items():
        ports = service.get("ports", [])
        require(name == "proxy" or not ports, "Only the proxy may publish host ports")
        for port in ports:
            require(isinstance(port, dict) and port.get("host_ip") == "127.0.0.1",
                    "Release rehearsal refuses non-loopback port bindings")
    require(len(services["proxy"].get("ports", [])) == 2, "Expected exactly two proxy loopback ports")


def validate_owned(containers, project):
    for container in containers:
        labels = container["Config"].get("Labels") or {}
        require(labels.get("com.docker.compose.project") == project
                and labels.get("com.docker.compose.service") in SERVICES
                and labels.get("hackatlas.release") == "true", "Refusing cleanup of unowned containers")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        return None


class Http:
    def __init__(self, base, ca, cookies=False):
        self.base = base
        self.context = ssl.create_default_context(cafile=str(ca))
        require(self.context.check_hostname and self.context.verify_mode == ssl.CERT_REQUIRED,
                "TLS certificate and hostname verification must stay enabled")
        self.cookies = http.cookiejar.CookieJar()
        handlers = [urllib.request.HTTPSHandler(context=self.context), NoRedirect(), urllib.request.ProxyHandler({})]
        if cookies:
            handlers.append(urllib.request.HTTPCookieProcessor(self.cookies))
        self.opener = urllib.request.build_opener(*handlers)

    def request(self, method, path, expected, data=None, token=None, form=None, headers=None):
        payload = None
        values = dict(headers or {})
        if token:
            values["Authorization"] = "Bearer " + token
        if data is not None:
            payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
            values["Content-Type"] = "application/json"
        if form is not None:
            payload = urllib.parse.urlencode(form).encode("ascii")
            values["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(self.base + path, data=payload, method=method, headers=values)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, response_headers, raw = response.status, response.headers, response.read()
        require(status == expected, f"Unexpected HTTP status for {method} {path.split('?')[0]}: {status}, expected {expected}")
        return response_headers, raw

    def json(self, method, path, expected, **kwargs):
        headers, raw = self.request(method, path, expected, **kwargs)
        return headers, json.loads(raw) if raw else None


class Rehearsal:
    def __init__(self, jar, output, timeout):
        self.jar, self.output, self.timeout = jar, output, timeout
        require(not any((output / name).exists() for name in ("report.json", "commands.log")),
                "Choose a fresh output directory; previous rehearsal evidence must not be overwritten")
        self.output.mkdir(parents=True, exist_ok=True)
        self.project = "hackatlas-b4-" + uuid.uuid4().hex[:12]
        self.temp = Path(tempfile.mkdtemp(prefix=self.project + "-"))
        os.chmod(self.temp, 0o700)
        self.redactions = []
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("HACKATLAS_", "SPRING_", "SERVER_", "COMPOSE_"))}
        self.http_port, self.https_port = free_port(), free_port()
        while self.http_port == self.https_port:
            self.https_port = free_port()
        self.env.update({"HACKATLAS_SECRET_DIR": str(self.temp / "secrets"), "HACKATLAS_SITE": "localhost",
                         "HACKATLAS_APP_IMAGE": self.project + "-app:local", "HACKATLAS_PROXY_IMAGE": self.project + "-proxy:local",
                         "HACKATLAS_HTTP_PORT": str(self.http_port), "HACKATLAS_HTTPS_PORT": str(self.https_port)})
        self.compose = ["docker", "compose", "--env-file", "/dev/null", "--project-name", self.project,
                        "-f", str(RELEASE / "compose.yaml"), "-f", str(RELEASE / "compose.local.yaml"),
                        "-f", str(self.temp / "build.json")]
        self.report = {"project": self.project, "result": "RUNNING", "externalDeployment": False,
                       "publicReleaseReady": False, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                       "checks": [], "limitations": [
                           "Local synthetic-data rehearsal only; no external host, production workload or mobile device was tested.",
                           "Measured times exclude operator response, off-site transfer, DNS, infrastructure loss and production data volume.",
                           "No scheduled or off-site backup exists; no production RPO/RTO or full account erasure is guaranteed."]}
        self.api = yaml.safe_load((ROOT / "api/openapi.yaml").read_text(encoding="utf-8"))
        self.started = False

    def redact(self, text):
        for value in sorted(self.redactions, key=len, reverse=True):
            text = text.replace(value, "[REDACTED]")
        return text

    def run(self, label, command, *, input_text=None, timeout=180, allow_failure=False):
        started = time.monotonic()
        result = subprocess.run(command, input=input_text, text=True, capture_output=True, cwd=ROOT,
                                env=self.env, timeout=timeout)
        with (self.output / "commands.log").open("a", encoding="utf-8") as log:
            log.write(f"{label}: exit={result.returncode}, seconds={time.monotonic()-started:.3f}\n")
            if result.returncode and result.stderr:
                log.write(self.redact(result.stderr[-12000:]) + "\n")
        require(allow_failure or result.returncode == 0, label + " failed; see redacted commands.log")
        return result

    def dc(self, label, *arguments, **kwargs):
        return self.run(label, self.compose + list(arguments), **kwargs)

    def passed(self, label):
        self.report["checks"].append(label)
        print("PASS " + label, flush=True)

    def setup(self):
        context = self.temp / "build"
        (context / "target").mkdir(parents=True)
        shutil.copy2(self.jar, context / "target/hackatlas-backend-0.1.0-SNAPSHOT.jar")
        shutil.copy2(ROOT / "backend/Dockerfile", context / "Dockerfile")
        (self.temp / "build.json").write_text(json.dumps({"services": {"app": {"build": {"context": str(context)}}}}), encoding="utf-8")
        self.run("Prepare disposable file secrets", [sys.executable, str(RELEASE / "ops/prepare_secrets.py"),
                                                    "--directory", str(self.temp / "secrets")])
        for name in SECRET_FILES:
            self.redactions.append((self.temp / "secrets" / name).read_text().strip())
        require((self.temp.stat().st_mode & 0o077) == 0, "Secrets parent must be private")
        config = json.loads(self.dc("Validate effective local Compose", "config", "--format", "json").stdout)
        validate_bindings(config)
        self.passed("effective Compose exposes only two loopback proxy ports")
        self.dc("Build exact tested application and proxy images", "build", "app", "proxy", timeout=900)
        self.report["images"] = {}
        for service, variable in (("app", "HACKATLAS_APP_IMAGE"), ("proxy", "HACKATLAS_PROXY_IMAGE")):
            tag = self.env[variable]
            inspect = json.loads(self.run("Inspect built " + service, ["docker", "image", "inspect", tag]).stdout)[0]
            self.report["images"][service] = {"tag": tag, "id": inspect["Id"]}
        self.started = True
        self.dc("Start isolated database", "up", "-d", "--wait", "--wait-timeout", str(self.timeout), "postgres", timeout=self.timeout + 60)
        tag = config["services"]["postgres"]["image"]
        inspect = json.loads(self.run("Inspect rehearsed PostgreSQL image", ["docker", "image", "inspect", tag]).stdout)[0]
        self.report["images"]["postgres"] = {"tag": tag, "id": inspect["Id"]}
        self.dc("Run migrations as separate migrator role", "--profile", "ops", "run", "--rm", "migrate", timeout=self.timeout)
        self.dc("Start least-privilege runtime and TLS proxy", "up", "-d", "--wait", "--wait-timeout", str(self.timeout), "app", "proxy", timeout=self.timeout + 60)
        self.dc("Export disposable Caddy root certificate", "cp", "proxy:/data/caddy/pki/authorities/local/root.crt", str(self.temp / "root.crt"))
        self.http = Http(f"https://localhost:{self.https_port}", self.temp / "root.crt")
        self.passed("separate migration process exits successfully and runtime/proxy health checks pass")

    def sql(self, statements, database="hackatlas", role="hackatlas_admin", allow_failure=False):
        result = self.dc("PostgreSQL rehearsal statement (input/output withheld)", "exec", "-T", "postgres", "psql",
                         "-X", "-qAt", "--set=ON_ERROR_STOP=1", "--set=VERBOSITY=sqlstate", "-U", role, "-d", database,
                         input_text=statements, allow_failure=allow_failure)
        return result if allow_failure else result.stdout.strip()

    def inspect_runtime(self):
        for service, uid in (("app", "10001:10001"), ("proxy", "10001:10001"), ("postgres", "999:999")):
            identifier = self.dc("Find owned " + service, "ps", "-q", service).stdout.strip()
            container = json.loads(self.run("Inspect owned " + service, ["docker", "inspect", identifier]).stdout)[0]
            validate_owned([container], self.project)
            self.report["images"][service]["containerImageId"] = container["Image"]
            require(container["Config"]["User"] == uid, service + " must run without root")
            require(container["HostConfig"]["ReadonlyRootfs"], service + " requires a read-only root filesystem")
            require("ALL" in container["HostConfig"].get("CapDrop", []), service + " capabilities must be dropped")
            require(any(option.startswith("no-new-privileges") for option in container["HostConfig"].get("SecurityOpt", [])),
                    service + " requires no-new-privileges")
            if service != "proxy":
                require(not container["HostConfig"].get("PortBindings"), service + " must not publish host ports")
            if service == "app":
                variables = dict(value.split("=", 1) for value in container["Config"]["Env"])
                require(variables.get("SPRING_PROFILES_ACTIVE") == "prod,release", "Runtime must use prod,release")
                require(variables.get("SPRING_FLYWAY_ENABLED") == "false", "Runtime must not run migrations")
                require("HACKATLAS_DB_PASSWORD" not in variables, "Database password must come from a mounted secret")
        self.passed("non-root/read-only/capability/secret/runtime isolation inspected on real containers")

    def check_http_baseline(self):
        headers, raw = self.http.request("GET", "/actuator/health/readiness", 200)
        require(json.loads(raw) == {"status": "UP"}, "Readiness must not expose database details")
        require("max-age=" in headers.get("Strict-Transport-Security", ""), "TLS endpoint requires HSTS")
        self.http.request("GET", "/actuator/env", 404)
        _, catalogue = self.http.json("GET", "/api/v1/hackathons", 200)
        require(catalogue["totalElements"] == 0, "Production must start without DEMO seed")
        self.password = secrets.token_hex(32)
        self.redactions.append(self.password)
        self.http.json("POST", "/api/v1/auth/register", 403,
                       data={"email": "closed@example.com", "password": self.password, "displayName": "Closed"})
        plain = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
        try:
            response = plain.open(f"http://localhost:{self.http_port}/admin", timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            require(response.status == 308 and response.headers.get("Location") == f"https://localhost:{self.https_port}/admin",
                    "HTTP must redirect to the explicitly trusted local HTTPS listener")
        self.passed("TLS/HSTS, HTTP redirect, minimal health, closed signup and absent production DEMO seed")

    def password_hash(self):
        libraries = self.temp / "hash-libs"
        libraries.mkdir()
        with zipfile.ZipFile(self.jar) as archive:
            for entry in archive.namelist():
                if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                    (libraries / Path(entry).name).write_bytes(archive.read(entry))
        source = self.temp / "RehearsalHash.java"
        source.write_text("""import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
class RehearsalHash { public static void main(String[] args) throws Exception {
  String password = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
  System.out.print(new Argon2PasswordEncoder(16,32,1,19456,2).encode(password));
} }
""", encoding="utf-8")
        self.run("Compile disposable fixture hash helper against tested JAR dependencies",
                 ["javac", "-cp", str(libraries / "*"), str(source)])
        hashed = self.run("Hash random synthetic credential (stdin/output withheld)",
                          ["java", "-cp", str(self.temp) + os.pathsep + str(libraries / "*"), "RehearsalHash"],
                          input_text=self.password).stdout.strip()
        require(hashed.startswith("$argon2id$"), "Fixture credential must use the application's Argon2id implementation")
        self.redactions.append(hashed)
        return hashed

    def seed_synthetic_data(self):
        fixture = json.loads((ROOT / "api/examples/hackathon-detail.json").read_text(encoding="utf-8"))
        metadata = json.loads((ROOT / "api/examples/filter-metadata.json").read_text(encoding="utf-8"))
        row = {"id": fixture["id"], "slug": fixture["slug"], "title": fixture["title"],
               "short_description": fixture["shortDescription"], "organizer_name": fixture["organizer"]["name"],
               "organizer_website_url": fixture["organizer"]["websiteUrl"], "format": fixture["format"],
               "country_code": fixture["location"]["countryCode"], "city": fixture["location"]["city"], "venue": fixture["location"]["venue"],
               "registration_status": fixture["registrationStatus"], "event_status": fixture["eventStatus"],
               "publication_status": "PUBLISHED", "published_at": "2026-10-12T10:00:00Z",
               "prize_status": fixture["prize"]["status"], "prize_total_amount": fixture["prize"]["totalAmount"],
               "prize_currency": fixture["prize"]["currency"], "prize_kind": fixture["prize"]["kind"], "prize_summary": fixture["prize"]["summary"],
               "team_min_size": fixture["team"]["minSize"], "team_max_size": fixture["team"]["maxSize"],
               "individual_application_allowed": fixture["team"]["individualApplicationAllowed"],
               "solo_participation_allowed": fixture["team"]["soloParticipationAllowed"], "team_formation_available": fixture["team"]["teamFormationAvailable"],
               "image_url": fixture["imageUrl"], "artwork_key": fixture["artworkKey"], "last_verified_at": fixture["lastVerifiedAt"],
               "updated_at": fixture["updatedAt"], "is_demo": True, "description": fixture["description"],
               "eligibility_summary": fixture["eligibility"]["summary"], "allowed_country_codes": fixture["eligibility"]["allowedCountryCodes"],
               "min_age": fixture["eligibility"]["minAge"], "max_age": fixture["eligibility"]["maxAge"],
               "students_only": fixture["eligibility"]["studentsOnly"], "languages": fixture["eligibility"]["languages"],
               "stages": fixture["stages"], "prize_details": fixture["prizeDetails"], "submission_requirements": fixture["submissionRequirements"],
               "judging_criteria": fixture["judgingCriteria"], "official_url": fixture["officialUrl"],
               "registration_url": fixture["registrationUrl"], "rules_url": fixture["rulesUrl"], "source_url": fixture["sourceUrl"]}
        for prefix, field in (("starts", "starts"), ("ends", "ends"), ("deadline", "registrationDeadline")):
            for suffix, key in (("precision", "precision"), ("date", "date"), ("time", "time"), ("time_zone", "timeZone"), ("instant", "instant")):
                row[prefix + "_" + suffix] = fixture[field][key] if fixture[field] else None
        # Only this copied, explicitly synthetic row is shifted; canonical fixtures and production Clock stay intact.
        today = datetime.now(timezone.utc).date()
        for prefix, days in (("starts", 30), ("ends", 32), ("deadline", 20)):
            row.update({prefix + "_precision": "DATE", prefix + "_date": (today + timedelta(days=days)).isoformat(),
                        prefix + "_time": None, prefix + "_time_zone": "UTC", prefix + "_instant": None})
        statements = ["BEGIN;"]
        for tag in {tag["id"]: tag for tag in metadata["topics"] + metadata["interests"]}.values():
            statements.append("INSERT INTO hackatlas.catalog_tag(id,label) VALUES(" + sql_value(tag["id"]) + "," + sql_value(tag["label"]) + ");")
        statements.append("INSERT INTO hackatlas.catalog_hackathon(" + ",".join(row) + ") VALUES(" + ",".join(sql_value(value) for value in row.values()) + ");")
        for tag in fixture["topics"]:
            statements.append("INSERT INTO hackatlas.catalog_hackathon_topic VALUES(" + sql_value(fixture["id"]) + "," + sql_value(tag["id"]) + ");")
        hashed = self.password_hash()
        self.users = [str(uuid.uuid4()), str(uuid.uuid4())]
        for index, user_id in enumerate(self.users):
            statements.append("INSERT INTO hackatlas.user_account(id,email,password_hash,display_name,created_at,updated_at) VALUES("
                              + ",".join(sql_value(value) for value in (user_id, f"rehearsal{index}@example.com", hashed, "Synthetic rehearsal"))
                              + ",now(),now());")
        statements += ["INSERT INTO hackatlas.admin_access(user_id) VALUES(" + sql_value(self.users[0]) + ");", "COMMIT;"]
        self.sql("\n".join(statements))
        self.event = fixture["id"]
        self.tokens = []
        for index in range(2):
            headers, auth = self.http.json("POST", "/api/v1/auth/login", 200,
                                          data={"email": f"rehearsal{index}@example.com", "password": self.password})
            require("no-store" in headers.get("Cache-Control", ""), "Token response must not be cached")
            self.tokens.append(auth["accessToken"])
            self.redactions.append(auth["accessToken"])
            self.http.request("PUT", "/api/v1/me/saved/" + self.event, 204, token=auth["accessToken"])
        self.passed("synthetic marked-DEMO catalogue and random synthetic accounts inserted only into owned disposable database")

    def schema(self, path, body):
        schema = {"$schema": "https://json-schema.org/draft/2020-12/schema",
                  **self.api["paths"][path]["get"]["responses"]["200"]["content"]["application/json"]["schema"],
                  "components": self.api["components"]}
        errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))
        require(not errors, "Canonical OpenAPI schema rejected public response " + path)

    def public_smoke(self):
        for path, operation in (("/hackathons", "/hackathons"), ("/hackathons/" + self.event, "/hackathons/{id}"),
                                ("/metadata/filters", "/metadata/filters")):
            _, body = self.http.json("GET", "/api/v1" + path, 200)
            self.schema(operation, body)
            if operation == "/hackathons":
                require(body["totalElements"] == 1 and body["items"][0]["id"] == self.event, "Published synthetic event must survive restore")
        self.passed("all three public GET operations pass canonical schemas through verified HTTPS")

    def admin_and_privileges(self):
        browser = Http(self.http.base, self.temp / "root.crt", cookies=True)
        headers, page = browser.request("GET", "/admin/login", 200)
        cookies = headers.get_all("Set-Cookie", [])
        require(any(all(flag in value.lower() for flag in ("secure", "httponly", "samesite=strict", "path=/admin")) for value in cookies),
                "Admin cookie must be Secure, HttpOnly, SameSite=Strict and scoped to /admin")
        def csrf(raw):
            match = re.search(r'<input(?=[^>]*name="_csrf")(?=[^>]*value="([^"]+)")[^>]*>', raw.decode("utf-8"))
            require(match is not None, "Admin form must contain a CSRF field")
            return match.group(1)
        browser.request("POST", "/admin/login", 403, form={"username": "rehearsal0@example.com", "password": self.password})
        _, page = browser.request("GET", "/admin/login", 200)
        headers, _ = browser.request("POST", "/admin/login", 302,
                                     form={"username": "rehearsal0@example.com", "password": self.password, "_csrf": csrf(page)})
        require(headers.get("Location") == "/admin", "Admin success redirect must stay relative behind untrusted proxy headers")
        _, dashboard = browser.request("GET", "/admin", 200)
        browser.request("POST", "/admin/events/" + self.event + "/verify", 302,
                        form={"_csrf": csrf(dashboard), "sourceChecked": "true"})
        browser.request("POST", "/admin/events/" + self.event + "/archive", 403, form={})
        browser.request("POST", "/admin/events/" + self.event + "/archive", 302, form={"_csrf": csrf(dashboard)})
        browser.request("POST", "/admin/events/" + self.event + "/publish", 302,
                        form={"_csrf": csrf(dashboard), "reviewConfirmed": "true"})
        headers, _ = self.http.request("GET", "/admin", 302, headers={"Forwarded": "for=203.0.113.9;proto=http;host=evil.invalid",
                                      "X-Forwarded-Host": "evil.invalid", "X-Forwarded-Proto": "http", "X-Forwarded-For": "203.0.113.9"})
        require(headers.get("Location") == "/admin/login", "Spoofed forwarding must not change admin redirects")
        # Runtime cannot even resolve the public schema; inspect ACLs as operator for that named role,
        # then prove actual runtime SQL is denied below.
        privileges = self.sql("SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname='hackatlas_runtime';\n"
                              "SELECT has_schema_privilege('hackatlas_runtime','hackatlas','CREATE');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','public.flyway_schema_history','SELECT');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','hackatlas.admin_access','UPDATE');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','hackatlas.admin_access','INSERT');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','hackatlas.admin_access','DELETE');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','hackatlas.admin_audit','UPDATE');\n"
                              "SELECT has_table_privilege('hackatlas_runtime','hackatlas.admin_audit','DELETE');")
        require(privileges.splitlines() == ["f"] * 8, "Runtime database role exceeds least-privilege allowlist")
        forbidden = self.sql("CREATE TABLE hackatlas.rehearsal_forbidden(id int);", role="hackatlas_runtime", allow_failure=True)
        require(forbidden.returncode != 0 and "42501" in forbidden.stderr, "Runtime DDL must fail with insufficient privilege")
        forbidden_read = self.sql("SELECT * FROM public.flyway_schema_history;", role="hackatlas_runtime", allow_failure=True)
        require(forbidden_read.returncode != 0 and "42501" in forbidden_read.stderr, "Runtime must not read migration history")
        for operation in ("SELECT * FROM", "DELETE FROM", "UPDATE"):
            statement = operation + " hackatlas.account_deletion_receipt"
            if operation == "UPDATE":
                statement += " SET recorded_at=now()"
            refused = self.sql(statement + ";", role="hackatlas_runtime", allow_failure=True)
            require(refused.returncode != 0 and "42501" in refused.stderr, "Runtime must not read or modify existing deletion receipts")
        self.passed("real admin login/CSRF/catalogue mutations work with least-privilege runtime; spoofed forwarding and privilege escalation denied")

    def snapshot(self, database):
        tables = self.sql("SELECT tablename FROM pg_tables WHERE schemaname='hackatlas' ORDER BY tablename;", database).splitlines()
        result = {}
        for table in tables:
            require(re.fullmatch(r"[a-z_]+", table), "Unexpected table identifier")
            rows = self.sql(f'SELECT row_to_json(t)::text FROM hackatlas."{table}" t;', database)
            result[table] = fingerprint(rows.splitlines() if rows else [])
        history = self.sql("SELECT row_to_json(t)::text FROM public.flyway_schema_history t;", database)
        result["flyway_schema_history"] = fingerprint(history.splitlines())
        return result

    def check_deletion_export_snapshot(self):
        self.absent_deletion = str(uuid.uuid4())
        record_id = str(uuid.uuid4())
        self.redactions.extend([self.absent_deletion, record_id])
        export_path = self.temp / "snapshot.deletion-export.json"
        export_command = [sys.executable, str(RELEASE / "ops/export_deletions.py"), "--project", self.project,
                          "--output", str(export_path)]
        # Keep a real transaction open after INSERT, without timing-based sleeps or timestamp watermarks.
        probe = subprocess.Popen(self.compose + ["exec", "-T", "postgres", "psql", "-X", "-qAt",
                                  "--set=ON_ERROR_STOP=1", "-U", "hackatlas_runtime", "-d", "hackatlas", "-f", "-"],
                                 env=self.env, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, text=True)
        try:
            probe.stdin.write("BEGIN; INSERT INTO hackatlas.account_deletion_receipt(id,account_id,recorded_at) VALUES ("
                              + sql_value(record_id) + "," + sql_value(self.absent_deletion)
                              + ",'2000-01-01T00:00:00Z'); SELECT 'B44_READY';\n")
            probe.stdin.flush()
            with selectors.DefaultSelector() as selector:
                selector.register(probe.stdout, selectors.EVENT_READ)
                require(bool(selector.select(timeout=15)), "Receipt probe must reach an uncommitted INSERT")
                require(probe.stdout.readline().strip() == "B44_READY", "Runtime INSERT receipt must be allowed")
            initial = json.loads(self.run("Export consistent source snapshot while receipt is uncommitted", export_command).stdout)
            require(initial["accountCount"] == 0 and initial["historyComplete"] is False,
                    "Uncommitted receipt must not appear or certify history completeness")
            probe.communicate("COMMIT;\n", timeout=15)
            require(probe.returncode == 0, "Controlled receipt transaction must commit")
        finally:
            if probe.poll() is None:
                try:
                    probe.communicate("ROLLBACK;\n", timeout=10)
                except subprocess.TimeoutExpired:
                    probe.kill()
                    probe.wait(timeout=10)
        late_path = self.temp / "late.deletion-export.json"
        late_command = export_command[:-1] + [str(late_path)]
        late = self.run("Full export includes late commit with old recorded_at", late_command)
        payload = json.loads(late_path.read_text(encoding="utf-8"))
        require(payload["deletedAccountIds"] == [self.absent_deletion] and json.loads(late.stdout)["accountCount"] == 1,
                "Full export must not lose a late commit with an old timestamp")
        require(self.absent_deletion not in late.stdout + late.stderr, "Export CLI must not log account UUIDs")
        require(self.run("Refuse deletion export overwrite", late_command, allow_failure=True).returncode != 0,
                "Export output must never be overwritten")
        marked_path = self.temp / "marked.deletion-export.json"
        self.sql("COMMENT ON DATABASE hackatlas IS '{\"kind\":\"hackatlas.restore\"}';", "postgres")
        try:
            require(self.run("Refuse marked/restored database as source", export_command[:-1] + [str(marked_path)],
                             allow_failure=True).returncode != 0 and not marked_path.exists(),
                    "Marked/restored database must not be accepted as an independent original source")
        finally:
            self.sql("COMMENT ON DATABASE hackatlas IS NULL;", "postgres")
        self.report["deletionExport"] = {"uncommittedExcluded": True, "lateCommitWithOldTimestampIncluded": True,
                                          "runtimeInsertOnly": True, "historyComplete": False}
        self.passed("real deletion snapshot excludes uncommitted rows, includes late commits and keeps runtime INSERT-only")

    def legacy_restore(self):
        previous = self.env.get("HACKATLAS_DB_NAME", "hackatlas")
        legacy = "hackatlas_legacy_" + uuid.uuid4().hex[:12]
        target = "hackatlas_restore_legacy_" + uuid.uuid4().hex[:12]
        try:
            self.sql(f'CREATE DATABASE "{legacy}" OWNER hackatlas_migrator;\nREVOKE ALL ON DATABASE "{legacy}" FROM PUBLIC;', "postgres")
            self.env["HACKATLAS_DB_NAME"] = legacy
            self.dc("Create genuine V6 schema for legacy backup regression", "--profile", "ops", "run", "--rm",
                    "-e", "SPRING_FLYWAY_TARGET=6", "-e", "SPRING_FLYWAY_LOCATIONS=classpath:db/migration", "migrate",
                    timeout=self.timeout)
            archive = self.temp / "legacy.dump"
            self.run("Back up legacy V6 schema without receipt table", [sys.executable, str(RELEASE / "ops/backup.py"),
                     "--project", self.project, "--database", legacy, "--output", str(archive)])
            restored = json.loads(self.run("Restore genuine V6 backup into quarantine", [sys.executable,
                str(RELEASE / "ops/restore.py"), "--project", self.project, "--input", str(archive), "--database", target]).stdout)
            require(restored["state"] == "QUARANTINED" and restored["runtimeAccessGranted"] is False,
                    "Legacy backup must restore without opening runtime access")
            require(self.sql("SELECT to_regclass('hackatlas.account_deletion_receipt') IS NULL;", target) == "t",
                    "Legacy backup must genuinely predate the receipt schema")
            self.env["HACKATLAS_DB_NAME"] = target
            self.dc("Migrate quarantined legacy restore from V6 to V7", "--profile", "ops", "run", "--rm", "migrate",
                    timeout=self.timeout)
            require(self.sql("SELECT count(*) FROM hackatlas.account_deletion_receipt;", target) == "0",
                    "Migration must not fabricate historical deletion receipts")
            require(self.sql("SELECT count(*) FROM public.flyway_schema_history WHERE version='7' AND success;", target) == "1",
                    "V7 must apply to a V6 restored database")
            require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                    "Legacy migration callback must preserve quarantine")
            self.report["legacyRestore"] = {"backupSchemaVersion": 6, "migratedToVersion": 7,
                                            "quarantinePreserved": True, "historicalReceiptsBackfilled": False}
            self.passed("genuine V6 backup restores and migrates to V7 without opening quarantine or inventing history")
        finally:
            self.env["HACKATLAS_DB_NAME"] = previous

    def backup_restore(self):
        self.check_deletion_export_snapshot()
        # Exercise cascade removal of every personal relation and submitted free text.
        self.sql("INSERT INTO hackatlas.user_interest(user_id,tag_id) VALUES(" + sql_value(self.users[1]) + ",'ai-ml');\n"
                 "INSERT INTO hackatlas.application_mark(user_id,hackathon_id,marked_at) VALUES(" + sql_value(self.users[1]) + "," + sql_value(self.event) + ",now());\n"
                 "INSERT INTO hackatlas.moderation_submission(id,user_id,kind,url,comment,created_at) VALUES("
                 + sql_value(str(uuid.uuid4())) + "," + sql_value(self.users[1])
                 + ",'SUGGESTION','https://example.com','Synthetic private review text',now());")
        backup_snapshot = self.snapshot("hackatlas")
        backup_path = self.temp / "release.dump"
        backup_command = [sys.executable, str(RELEASE / "ops/backup.py"), "--project", self.project, "--output", str(backup_path)]
        self.report["backup"] = json.loads(self.run("Create full custom-format backup", backup_command).stdout)
        require(backup_path.stat().st_mode & 0o077 == 0, "Backup must be private")
        require(self.run("Refuse backup overwrite", backup_command, allow_failure=True).returncode != 0, "Backup overwrite must be refused")
        archive = RecoveryArchiveChecks(self, require)
        archive.save_backup(backup_path)
        self.http.request("POST", "/api/v1/me/delete-account", 204, token=self.tokens[1], data={"password": self.password})
        post_delete_source = self.snapshot("hackatlas")
        require(post_delete_source["user_account"]["rows"] == 1, "Post-backup synthetic deletion must reach source database")
        self.dc("Freeze owned source application writers for operator review", "stop", "--timeout", "30", "app")
        export_path = self.temp / "source.deletion-export.json"
        exported = self.run("Export all source receipts after actual API deletion and write freeze",
                            [sys.executable, str(RELEASE / "ops/export_deletions.py"), "--project", self.project,
                             "--output", str(export_path)])
        exported_bytes = export_path.read_bytes()
        payload = json.loads(exported_bytes)
        require(payload["deletedAccountIds"] == sorted([self.absent_deletion, self.users[1]]),
                "Source export must include API receipt and known absent UUID")
        require(post_delete_source["account_deletion_receipt"]["rows"] == 2,
                "API deletion must atomically produce a source receipt")
        require(all(identifier not in exported.stdout + exported.stderr for identifier in payload["deletedAccountIds"]),
                "Private UUIDs must not appear in export command output")
        backup_path, export_path = archive.save_later_and_extract(backup_path, export_path)
        target = "hackatlas_restore_" + uuid.uuid4().hex[:12]
        restore = [sys.executable, str(RELEASE / "ops/restore.py"), "--project", self.project,
                   "--input", str(backup_path), "--database", target]
        invalid = self.temp / "invalid.dump"
        shutil.copyfile(backup_path, invalid)
        Path(str(invalid) + ".sha256").write_text("0" * 64, encoding="ascii")
        refused = self.run("Refuse checksum-corrupted backup", restore[:-3] + [str(invalid), "--database", target], allow_failure=True)
        require(refused.returncode != 0, "Corrupt backup must be rejected")
        require(not self.sql("SELECT 1 FROM pg_database WHERE datname=" + sql_value(target) + ";", "postgres"), "Invalid backup must not create a database")
        started = time.monotonic()
        self.report["restore"] = json.loads(self.run("Restore into a new isolated database", restore).stdout)
        require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                "Restored runtime CONNECT must remain denied until privacy review")
        require(self.report["restore"].get("readyForSmoke") is False
                and self.report["restore"].get("runtimeAccessGranted") is False
                and self.report["restore"].get("state") == "QUARANTINED"
                and self.report["restore"].get("requiresPrivacyReview") is True,
                "Restore must remain quarantined until an explicit operator review")
        require(self.sql("SELECT 1;", target, role="hackatlas_runtime", allow_failure=True).returncode != 0,
                "A real runtime connection must be refused while quarantined")
        require(self.report["restore"]["revokedSessions"] == backup_snapshot["auth_session"]["rows"], "Every backed-up session must be revoked")
        require(self.run("Refuse restore overwrite", restore, allow_failure=True).returncode != 0, "Existing restore target must be refused")
        restored = self.snapshot(target)
        require(restored["auth_session"]["rows"] == 0, "Restored database must contain no bearer sessions")
        require({key: value for key, value in restored.items() if key != "auth_session"}
                == {key: value for key, value in backup_snapshot.items() if key != "auth_session"}, "Restored table hashes/counts must match the backup snapshot")
        require(self.snapshot("hackatlas") == post_delete_source, "Restore must leave source database unchanged")
        self.env["HACKATLAS_DB_NAME"] = target
        self.dc("Validate restored migrations as migrator", "--profile", "ops", "run", "--rm", "migrate", timeout=self.timeout)
        require(self.snapshot(target)["flyway_schema_history"] == restored["flyway_schema_history"], "Restored Flyway history must validate without reapplying migrations")
        require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                "Migration callbacks must not bypass quarantine")
        review_path = self.temp / "privacy.restore-review.json"
        approve = [sys.executable, str(RELEASE / "ops/approve_restore.py"), "--project", self.project,
                   "--database", target, "--review", str(review_path)]
        require(self.run("Refuse missing operator review", approve, allow_failure=True).returncode != 0,
                "Missing review must not open the restore")
        prepare = [sys.executable, str(RELEASE / "ops/prepare_restore_review.py"), "--export", str(export_path),
                   "--source-project", self.project, "--source-database", "hackatlas", "--project", self.project,
                   "--database", target, "--output", str(review_path)]
        wrong_source = list(prepare)
        wrong_source[wrong_source.index("--source-project") + 1] = "another-project"
        require(self.run("Refuse export source identity mismatch", wrong_source, allow_failure=True).returncode != 0
                and not review_path.exists(), "Wrong source must not create review output")
        prepared = self.run("Prepare private unapproved restore review from export", prepare)
        draft = json.loads(review_path.read_text(encoding="utf-8"))
        require(draft["sourceWritesStopped"] is False and draft["deletionsReviewed"] is False
                and draft["reviewedThrough"] is None and draft["deletedAccountIds"] == payload["deletedAccountIds"],
                "Prepared draft must not silently attest source freeze or complete review")
        require(all(identifier not in prepared.stdout + prepared.stderr for identifier in payload["deletedAccountIds"]),
                "Review preparation must not print private UUIDs")
        require(self.run("Refuse unapproved generated draft", approve, allow_failure=True).returncode != 0,
                "Generated draft must never open runtime access")
        require(self.run("Refuse private draft overwrite", prepare, allow_failure=True).returncode != 0,
                "Review preparation must never overwrite an existing private file")
        require(self.snapshot(target) == restored, "Preparing or rejecting a draft must not mutate quarantined data")
        require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                "Draft preparation/rejection must preserve quarantine")
        review = {"formatVersion": 1, "project": self.project, "database": target,
                  "backupSha256": self.report["restore"]["sha256"], "restoreId": self.report["restore"]["restoreId"],
                  "sourceWritesStopped": True, "deletionsReviewed": True,
                  "reviewedThrough": datetime.now(timezone.utc).isoformat(), "deletedAccountIds": draft["deletedAccountIds"]}
        self.redactions.extend(review["deletedAccountIds"])
        review_path.write_text('{"invalid":', encoding="utf-8")
        os.chmod(review_path, 0o600)
        require(self.run("Refuse malformed operator review", approve, allow_failure=True).returncode != 0,
                "Malformed review must not open the restore")
        for field, value in (("backupSha256", "0" * 64), ("database", "hackatlas_restore_other"),
                             ("project", "another-project"), ("restoreId", str(uuid.uuid4())),
                             ("deletionsReviewed", False), ("sourceWritesStopped", False)):
            invalid_review = {**review, field: value}
            review_path.write_text(json.dumps(invalid_review), encoding="utf-8")
            require(self.run("Refuse mismatched/incomplete review: " + field, approve, allow_failure=True).returncode != 0,
                    "Review identity and explicit confirmations must be enforced")
            require(self.snapshot(target) == restored, "Rejected reviews must not mutate restored data")
            require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                    "Rejected reviews must keep runtime access denied")
        review_path.write_text(json.dumps(review), encoding="utf-8")
        metadata_sql = "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=current_database();"
        expected_state = json.loads(self.sql(metadata_sql, target))
        stale_approval = approval_sql(target, expected_state, review["deletedAccountIds"], review["reviewedThrough"])
        changed_state = {**expected_state, "restoreId": str(uuid.uuid4())}
        self.sql(f'COMMENT ON DATABASE "{target}" IS ' + sql_value(json.dumps(changed_state)) + ";", "postgres")
        require(self.sql(stale_approval, target, role="hackatlas_migrator", allow_failure=True).returncode != 0,
                "Atomic approval must recheck metadata changed after validation")
        require(self.snapshot(target) == restored, "Stale metadata must not cause partial account deletion")
        self.sql(f'COMMENT ON DATABASE "{target}" IS ' + sql_value(json.dumps(expected_state)) + ";", "postgres")
        self.sql(f'GRANT CONNECT ON DATABASE "{target}" TO hackatlas_runtime;', "postgres")
        require(self.run("Refuse pre-opened restore runtime ACL", approve, allow_failure=True).returncode != 0,
                "Approval must not operate on a pre-opened database")
        self.sql(f'REVOKE CONNECT ON DATABASE "{target}" FROM hackatlas_runtime;', "postgres")
        # REVOKE alone does not disconnect an already connected client.
        self.sql(f'GRANT CONNECT ON DATABASE "{target}" TO hackatlas_runtime;', "postgres")
        probe = subprocess.Popen(self.compose + ["exec", "-T", "-e", "PGAPPNAME=b42-quarantine-probe", "postgres",
                                  "psql", "-X", "-qAt", "-U", "hackatlas_runtime", "-d", target, "-c", "SELECT pg_sleep(30);"],
                                 env=self.env, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        probe_sql = "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name='b42-quarantine-probe';"
        try:
            deadline = time.monotonic() + 8
            while self.sql(probe_sql, target) == "0" and time.monotonic() < deadline:
                time.sleep(0.1)
            require(self.sql(probe_sql, target) == "1", "Owned runtime probe must establish a real connection")
            self.sql(f'REVOKE CONNECT ON DATABASE "{target}" FROM hackatlas_runtime;', "postgres")
            require(self.run("Refuse a runtime session surviving CONNECT revocation", approve, allow_failure=True).returncode != 0,
                    "An active runtime session must keep the restore unapproved")
            require(self.snapshot(target) == restored, "Active-session refusal must not change restored data")
        finally:
            self.sql("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND application_name='b42-quarantine-probe';", target)
            try:
                probe.wait(timeout=10)
            except subprocess.TimeoutExpired:
                probe.kill()
                probe.wait(timeout=10)
        self.sql("ALTER TABLE hackatlas.admin_audit ADD CONSTRAINT b42_fault_audit CHECK (action <> 'ACCOUNT_DELETED') NOT VALID;", target)
        require(self.run("Atomic approval must fail on injected audit constraint", approve, allow_failure=True).returncode != 0,
                "Injected transactional failure must be reported")
        require(self.snapshot(target) == restored, "Failed approval must roll back account deletion and audit")
        require(self.sql("SELECT has_database_privilege('hackatlas_runtime', " + sql_value(target) + ", 'CONNECT');", "postgres") == "f",
                "Failed approval must not grant CONNECT")
        require(json.loads(self.sql("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=current_database();", target))["state"] == "QUARANTINED",
                "Failed approval must preserve quarantined metadata")
        self.sql("ALTER TABLE hackatlas.admin_audit DROP CONSTRAINT b42_fault_audit;", target)
        self.report["restoreApproval"] = json.loads(self.run("Apply complete private operator review atomically", approve).stdout)
        require(self.report["restoreApproval"]["deletedAccounts"] == 1
                and self.report["restoreApproval"]["reviewedAccountIds"] == 2
                and self.report["restoreApproval"]["operatorReviewAccepted"] is True,
                "The late-deleted account must be removed before opening runtime access")
        require(self.run("Refuse repeated restore approval", approve, allow_failure=True).returncode != 0,
                "An already approved restore must not accept a replay")
        reconciled = self.snapshot(target)
        require(reconciled["user_account"]["rows"] == 1 and reconciled["auth_session"]["rows"] == 0,
                "Only the retained account may survive and all old sessions must remain revoked")
        require(all(reconciled[table]["rows"] == 0 for table in ("user_interest", "application_mark", "moderation_submission")),
                "Late-deletion reconciliation must remove profile relations and submitted private text")
        require(reconciled["account_deletion_receipt"] == backup_snapshot["account_deletion_receipt"],
                "Restore must preserve snapshot receipts, not invent a complete independent history")
        require(export_path.read_bytes() == exported_bytes, "Review/approval must not rewrite the private source export")
        require({k: v for k, v in reconciled.items() if k not in {"auth_session", "admin_audit", "account_deletion_receipt"}}
                == {k: v for k, v in post_delete_source.items() if k not in {"auth_session", "admin_audit", "account_deletion_receipt"}},
                "Reconciled target must match source post-deletion data")
        require(self.sql("SELECT count(*) FROM hackatlas.admin_audit WHERE action='ACCOUNT_DELETED' AND actor_id IS NULL AND event_id IS NULL;", target) == "1",
                "Reconciliation audit must not include deleted account identifiers")
        require(self.snapshot("hackatlas") == post_delete_source, "Approval must not modify source database")
        self.report["dataFingerprints"] = reconciled
        self.report["postBackupDeletion"] = {"sourceAccountRows": 1, "snapshotRestoredAccountRows": 2,
                                            "approvedAccountRows": 1, "lateDeletionAppliedBeforeRuntimeAccess": True,
                                            "independentDeletionReviewIsManual": True}
        self.report["limitations"].append("Independent deletion-list completeness and source write freeze are operator attestations; the synthetic review is not an automated deletion ledger or production privacy guarantee.")
        self.dc("Switch disposable runtime to restored database", "up", "-d", "--no-deps", "--force-recreate", "--wait",
                "--wait-timeout", str(self.timeout), "app", timeout=self.timeout + 60)
        self.wait_https()
        for token in self.tokens:
            self.http.json("GET", "/api/v1/me", 401, token=token)
        self.http.json("POST", "/api/v1/auth/login", 401,
                       data={"email": "rehearsal1@example.com", "password": self.password})
        _, retained = self.http.json("POST", "/api/v1/auth/login", 200,
                                    data={"email": "rehearsal0@example.com", "password": self.password})
        self.redactions.append(retained["accessToken"])
        self.http.json("GET", "/api/v1/me", 200, token=retained["accessToken"])
        self.public_smoke()
        self.report["restore"]["localRecoveryThroughHttpsSeconds"] = round(time.monotonic() - started, 3)
        self.report["backup"].pop("output", None)
        self.report["backup"].pop("checksum", None)
        self.report["restore"].pop("sourceArchive", None)
        self.passed("quarantined restore, failed/replayed review refusal, atomic late deletion, source isolation and post-approval HTTPS/auth")

    def wait_https(self):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            try:
                self.http.request("GET", "/actuator/health/readiness", 200)
                return
            except (OSError, urllib.error.URLError, RehearsalFailure):
                time.sleep(1)
        raise RehearsalFailure("Restored runtime did not become ready through verified HTTPS")

    def cleanup(self):
        if self.started:
            identifiers = self.run("Find owned containers for cleanup", ["docker", "ps", "-aq", "--filter",
                                   "label=com.docker.compose.project=" + self.project]).stdout.split()
            if identifiers:
                containers = json.loads(self.run("Check cleanup ownership", ["docker", "inspect", *identifiers]).stdout)
                validate_owned(containers, self.project)
            self.dc("Remove only this rehearsal's containers and volumes", "down", "--volumes", "--timeout", "35", timeout=180)
            remaining = self.run("Verify owned containers removed", ["docker", "ps", "-aq", "--filter",
                                 "label=com.docker.compose.project=" + self.project]).stdout.strip()
            require(not remaining, "Owned rehearsal containers remain after cleanup")
        require(self.temp.parent == Path(tempfile.gettempdir()).resolve() and self.temp.name.startswith(self.project + "-"),
                "Refusing cleanup of an unexpected temporary path")
        shutil.rmtree(self.temp)
        self.report["cleanup"] = {"containersAndVolumesRemoved": True, "temporarySecretsAndBackupRemoved": True,
                                  "builtImagesRetainedForAudit": bool(self.report.get("images"))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=240, help="Health/migration readiness timeout in seconds")
    args = parser.parse_args()
    require(os.name == "posix", "Run this rehearsal in Linux/WSL")
    require(args.timeout >= 30, "Readiness timeout must be at least 30 seconds")
    for executable in ("docker", "java", "javac"):
        require(shutil.which(executable), executable + " is required")
    verify_binary(ROOT / 'backend/target/restic-0.19.1')
    rehearsal = Rehearsal(args.jar.resolve(strict=True), args.output_dir.resolve(), args.timeout)
    started = time.monotonic()
    try:
        rehearsal.setup()
        rehearsal.inspect_runtime()
        rehearsal.check_http_baseline()
        rehearsal.seed_synthetic_data()
        rehearsal.admin_and_privileges()
        rehearsal.public_smoke()
        rehearsal.backup_restore()
        rehearsal.legacy_restore()
        rehearsal.report["result"] = "PASS"
    except Exception as error:
        rehearsal.report["result"] = "FAIL"
        rehearsal.report["failure"] = str(error) if isinstance(error, RehearsalFailure) else type(error).__name__
        print("FAIL " + rehearsal.report["failure"], flush=True)
    finally:
        try:
            rehearsal.cleanup()
        except Exception as error:
            rehearsal.report["result"] = "FAIL"
            rehearsal.report["cleanup"] = {"completed": False, "reason": type(error).__name__, "ownedTemporaryDirectory": str(rehearsal.temp)}
        rehearsal.report["totalSeconds"] = round(time.monotonic() - started, 3)
        (rehearsal.output / "report.json").write_text(json.dumps(rehearsal.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Report: " + str(rehearsal.output / "report.json"), flush=True)
    return 0 if rehearsal.report["result"] == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RehearsalFailure) as error:
        sys.exit("Rehearsal prerequisite failed: " + str(error))
