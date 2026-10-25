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


def check_project_invariants(api):
    """Guard agreed v0.1.0 decisions which the OpenAPI standard cannot enforce."""
    schemes = api["components"]["securitySchemes"]
    if set(schemes) != {"BearerSession"}:
        raise ValueError("Only the agreed BearerSession security scheme is supported.")
    session = schemes["BearerSession"]
    if any(session.get(key) != expected for key, expected in
           {"type": "http", "scheme": "bearer", "bearerFormat": "opaque"}.items()):
        raise ValueError("BearerSession must be an opaque HTTP bearer session, not JWT.")

    b1_operations = set()
    auth_operations = set()
    for path, path_item in api["paths"].items():
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            route = (method, path)
            phase = operation.get("x-phase")
            if phase not in {"B1", "B2", "B3"}:
                raise ValueError(f"{method.upper()} {path}: missing/unknown x-phase.")
            if phase == "B1":
                b1_operations.add(route)
            if path.startswith("/auth/"):
                auth_operations.add(route)
                if phase != "B2":
                    raise ValueError(f"{method.upper()} {path}: auth belongs to B2.")
            if "refresh" in path.casefold():
                raise ValueError(f"{path}: v0.1.0 has no refresh endpoint.")
            expected_security = [] if route in PUBLIC_CATALOG | PUBLIC_AUTH else [
                {"BearerSession": []}
            ]
            security = operation.get("security", api.get("security", []))
            if security != expected_security:
                raise ValueError(f"{method.upper()} {path}: unexpected public/session access.")
    if b1_operations != PUBLIC_CATALOG:
        raise ValueError("B1 must contain exactly the three agreed public catalogue operations.")
    if auth_operations != AUTH_OPERATIONS:
        raise ValueError("Auth must contain only POST register/login/logout; no refresh flow.")

    auth_properties = api["components"]["schemas"]["AuthResponse"]["properties"]
    if "refreshToken" in auth_properties:
        raise ValueError("AuthResponse must not introduce a refresh token.")
    if auth_properties["tokenType"].get("enum") != ["Bearer"]:
        raise ValueError("AuthResponse.tokenType must be Bearer.")


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
    print("PASS x-phase, public B1 catalogue and opaque bearer auth without refresh")
    for schema in api["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    manifest = json.loads((ROOT / "api/examples/manifest.json").read_text(encoding="utf8"))
    for filename, name in manifest.items():
        schema = {"$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$ref": f"#/components/schemas/{name}", "components": api["components"]}
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        data = json.loads((ROOT / "api/examples" / filename).read_text(encoding="utf8"))
        errors = sorted(validator.iter_errors(data), key=lambda e: str(list(e.path)))
        if errors:
            for error in errors:
                print(f"FAIL {filename} {list(error.path)}: {error.message}", file=sys.stderr)
            return 1
        print(f"PASS {filename}: {name}")
    print("PASS contract and demo schemas; server/database/build not tested.")
    return 0
if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, yaml.YAMLError, SchemaError, ValidationError) as exc:
        raise SystemExit(f"Contract validation failed: {exc}") from exc
