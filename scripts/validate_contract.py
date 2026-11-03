#!/usr/bin/env python3
"""Validate OpenAPI 3.1, HackAtlas contract invariants and demo JSON instances.

Install scripts/requirements-contract.txt in a virtual environment first.
This checks the contract, not a running server, database or application build.
"""
from pathlib import Path
import json
import sys
try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
    from jsonschema.exceptions import SchemaError, ValidationError
    from openapi_spec_validator import OpenAPIV31SpecValidator, validate
except ImportError:
    sys.exit("Install scripts/requirements-contract.txt in a virtual environment first.")

ROOT = Path(__file__).resolve().parents[1]
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
PUBLIC_CATALOG = {("get", "/hackathons"), ("get", "/hackathons/{id}"),
                  ("get", "/metadata/filters")}
PUBLIC_AUTH = {("post", "/auth/register"), ("post", "/auth/login")}
AUTH_OPERATIONS = PUBLIC_AUTH | {("post", "/auth/logout")}
LIFECYCLE_PUBLIC = {("post", "/auth/password-reset/" + action) for action in ("request", "verify", "confirm")}
LIFECYCLE_PRIVATE = {("post", "/auth/email-verification/" + action) for action in ("request", "confirm")}
PLANNED_LIFECYCLE = LIFECYCLE_PUBLIC | LIFECYCLE_PRIVATE
B2_OPERATIONS = AUTH_OPERATIONS | {("get", "/me"), ("patch", "/me"),
    ("get", "/me/saved"), ("put", "/me/saved/{id}"), ("delete", "/me/saved/{id}"),
    ("get", "/me/applications"), ("put", "/me/applications/{id}"), ("delete", "/me/applications/{id}")}
B3_OPERATIONS = {("post", "/suggestions"), ("post", "/hackathons/{id}/reports"), ("post", "/me/delete-account")}
PROFILE_REQUIRED = {"id", "email", "displayName", "city", "countryCode", "roleLabel", "avatarKey", "interests", "stats"}
LEGACY_CODES = {"VALIDATION_ERROR", "AUTH_REQUIRED", "INVALID_CREDENTIALS", "FORBIDDEN", "NOT_FOUND",
                "EMAIL_ALREADY_EXISTS", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE"}
LIFECYCLE_CODES = {"EMAIL_NOT_VERIFIED", "EMAIL_ACTION_INVALID", "PASSWORD_RESET_REQUIRED"}
LEGACY_EXAMPLES = {"catalog-page.json": "HackathonPage", "catalog-empty.json": "HackathonPage",
                   "hackathon-detail.json": "HackathonDetail", "profile.json": "Profile",
                   "filter-metadata.json": "FilterMetadata", "validation-error.json": "Problem"}
LIFECYCLE_EXAMPLES = {"profile-pending.json": "Profile", "profile-verified.json": "Profile",
    "email-action-challenge.json": "EmailActionChallenge", "password-reset-grant.json": "PasswordResetGrant",
    "invalid-email-action.json": "LifecycleProblem", "email-not-verified.json": "LifecycleProblem",
    "password-reset-required.json": "LifecycleProblem"}


def check_project_invariants(api):
    """Guard the exact legacy surface and separately declared planned lifecycle surface."""
    if (api["info"]["version"] != "0.1.1" or api.get("x-account-lifecycle-stage") != "contract-only"
            or api.get("x-email-verification-enforced") is not False):
        raise ValueError("This stage is contract-only 0.1.1; lifecycle activation is a separate task.")
    schemes = api["components"]["securitySchemes"]
    if set(schemes) != {"BearerSession"}:
        raise ValueError("Only the agreed BearerSession security scheme is supported.")
    session = schemes["BearerSession"]
    if any(session.get(key) != expected for key, expected in
           {"type": "http", "scheme": "bearer", "bearerFormat": "opaque"}.items()):
        raise ValueError("BearerSession must be an opaque HTTP bearer session, not JWT.")

    phases = {name: set() for name in ("B1", "B2", "B3", "ACCOUNT_LIFECYCLE")}
    auth_operations = set()
    for path, path_item in api["paths"].items():
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            route = (method, path)
            phase = operation.get("x-phase")
            if phase not in phases:
                raise ValueError(f"{method.upper()} {path}: missing/unknown x-phase.")
            phases[phase].add(route)
            if route in PLANNED_LIFECYCLE:
                if phase != "ACCOUNT_LIFECYCLE" or operation.get("x-implementation-status") != "planned":
                    raise ValueError(f"{path}: lifecycle must remain explicitly planned, outside B2 coverage.")
            elif phase == "ACCOUNT_LIFECYCLE" or operation.get("x-implementation-status", "implemented") != "implemented":
                raise ValueError(f"{path}: a planned marker cannot hide an existing or unknown operation.")
            if path.startswith("/auth/") and route not in PLANNED_LIFECYCLE:
                auth_operations.add(route)
                if phase != "B2":
                    raise ValueError(f"{method.upper()} {path}: auth belongs to B2.")
            if "refresh" in path.casefold():
                raise ValueError(f"{path}: opaque sessions have no refresh endpoint.")
            expected_security = [] if route in PUBLIC_CATALOG | PUBLIC_AUTH | LIFECYCLE_PUBLIC else [
                {"BearerSession": []}
            ]
            security = operation.get("security", api.get("security", []))
            if security != expected_security:
                raise ValueError(f"{method.upper()} {path}: unexpected public/session access.")
    if phases["B1"] != PUBLIC_CATALOG:
        raise ValueError("B1 must contain exactly the three agreed public catalogue operations.")
    if phases["B2"] != B2_OPERATIONS or phases["B3"] != B3_OPERATIONS:
        raise ValueError("Existing eleven B2 and three B3 operations must retain their phases and coverage.")
    if phases["ACCOUNT_LIFECYCLE"] != PLANNED_LIFECYCLE:
        raise ValueError("Exactly five agreed lifecycle POST operations must be declared as planned.")
    if auth_operations != AUTH_OPERATIONS:
        raise ValueError("Auth must contain only POST register/login/logout; no refresh flow.")

    auth_properties = api["components"]["schemas"]["AuthResponse"]["properties"]
    if set(auth_properties) != {"tokenType", "accessToken", "expiresAt", "user"}:
        raise ValueError("AuthResponse must retain its envelope; no refresh/reset grant fields.")
    if auth_properties["tokenType"].get("enum") != ["Bearer"]:
        raise ValueError("AuthResponse.tokenType must be Bearer.")

    schemas = api["components"]["schemas"]
    profile = schemas["Profile"]
    if set(profile["required"]) != PROFILE_REQUIRED or profile.get("additionalProperties") is not False:
        raise ValueError("Keep all old Profile requirements and its closed shape; emailVerified is optional here.")
    flag = profile["properties"].get("emailVerified", {})
    if (flag.get("type") != "boolean" or flag.get("readOnly") is not True
            or any(key in flag for key in ("default", "enum", "const", "anyOf", "oneOf"))):
        raise ValueError("emailVerified must be an optional non-null boolean, without a verification default.")
    for request in ("UpdateProfile", "RegisterRequest"):
        if "emailVerified" in schemas[request]["properties"] or schemas[request].get("additionalProperties") is not False:
            raise ValueError("Clients cannot assign verification through registration/profile updates.")
    codes = schemas["Problem"]["properties"]["code"]["enum"]
    if set(codes) != LEGACY_CODES or len(codes) != len(LEGACY_CODES):
        raise ValueError("Do not widen existing Problem responses with planned lifecycle errors.")
    lifecycle_problem = schemas["LifecycleProblem"]
    planned_codes = lifecycle_problem["properties"]["code"]["enum"]
    if set(planned_codes) != LEGACY_CODES | LIFECYCLE_CODES or len(planned_codes) != len(LEGACY_CODES | LIFECYCLE_CODES):
        raise ValueError("LifecycleProblem must include existing and exactly three planned lifecycle codes.")
    if (lifecycle_problem.get("additionalProperties") is not False
            or lifecycle_problem.get("required") != schemas["Problem"]["required"]
            or {k: v for k, v in lifecycle_problem["properties"].items() if k != "code"}
            != {k: v for k, v in schemas["Problem"]["properties"].items() if k != "code"}
            or {k: v for k, v in lifecycle_problem["properties"]["code"].items() if k != "enum"}
            != {k: v for k, v in schemas["Problem"]["properties"]["code"].items() if k != "enum"}):
        raise ValueError("LifecycleProblem must keep the same closed wire shape and required fields.")
    grant = schemas["PasswordResetGrant"]
    if set(grant["properties"]) != {"resetToken", "expiresAt"} or grant.get("additionalProperties") is not False:
        raise ValueError("A reset-only grant is not an authentication response.")


def validate_example_manifests(api, directory):
    """Keep the six mobile fixtures stable; validate lifecycle fixtures independently."""
    count = 0
    for relative, required, exact in (("manifest.json", LEGACY_EXAMPLES, True),
                                      ("account-lifecycle/manifest.json", LIFECYCLE_EXAMPLES, False)):
        manifest_path = directory / relative
        manifest = json.loads(manifest_path.read_text(encoding="utf8"))
        if not isinstance(manifest, dict) or (exact and manifest != required):
            raise ValueError("The legacy manifest must retain the six current mobile fixtures.")
        if any(manifest.get(name) != schema for name, schema in required.items()):
            raise ValueError("Required lifecycle examples are missing or mapped to the wrong schema.")
        for filename, name in manifest.items():
            if not isinstance(filename, str) or Path(filename).name != filename or not filename.endswith(".json"):
                raise ValueError("Example paths must be JSON basenames within their manifest directory.")
            if name not in api["components"]["schemas"]:
                raise ValueError("Example references an unknown schema.")
            schema = {"$schema": "https://json-schema.org/draft/2020-12/schema",
                      "$ref": f"#/components/schemas/{name}", "components": api["components"]}
            validator = Draft202012Validator(schema, format_checker=FormatChecker())
            data = json.loads((manifest_path.parent / filename).read_text(encoding="utf8"))
            validator.validate(data)
            print(f"PASS {relative}: {filename}: {name}")
            count += 1
    return count


def main() -> int:
    api = yaml.safe_load((ROOT / "api/openapi.yaml").read_text(encoding="utf8"))
    def check_refs(value):
        if isinstance(value, dict):
            if "$ref" in value:
                ref = value["$ref"]
                if not ref.startswith("#/"):
                    raise ValueError(f"External ref requires review: {ref}")
                node = api
                for part in ref[2:].split("/"):
                    node = node[part.replace("~1", "/").replace("~0", "~")]
            for child in value.values():
                check_refs(child)
        elif isinstance(value, list):
            for child in value:
                check_refs(child)
    check_refs(api)
    validate(api, cls=OpenAPIV31SpecValidator)
    print("PASS OpenAPI 3.1, local references, operation IDs and path parameters")
    check_project_invariants(api)
    print("PASS exact B1/B2/B3 coverage, planned lifecycle, public/session access and opaque auth without refresh")
    for schema in api["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    validate_example_manifests(api, ROOT / "api/examples")
    print("PASS contract and demo schemas; server/database/build not tested.")
    return 0
if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, yaml.YAMLError, SchemaError, ValidationError) as exc:
        raise SystemExit(f"Contract validation failed: {exc}") from exc
