#!/usr/bin/env python3
"""Check local OpenAPI references and demo JSON instances. Does not run a server.
Requires PyYAML and jsonschema. Run from any directory: python scripts/validate_contract.py.
This is not a full OpenAPI-spec validator nor an integration test.
"""
from pathlib import Path
import json
import sys
try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    sys.exit("Install PyYAML and jsonschema in a virtual environment before running this checker.")
ROOT = Path(__file__).resolve().parents[1]
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
    print("PASS local references and demo schemas; server/build not tested.")
    return 0
if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError) as exc:
        raise SystemExit(f"Contract validation failed: {exc}") from exc
