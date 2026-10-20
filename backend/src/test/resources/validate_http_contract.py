"""Validate exported real HTTP responses against the canonical OpenAPI 3.1 contract."""

import argparse
import json
from pathlib import Path
import sys

import yaml
from jsonschema import Draft202012Validator, FormatChecker


REQUIRED_COVERAGE = {
    ("/hackathons", 200),
    ("/hackathons/{id}", 200),
    ("/metadata/filters", 200),
    ("/hackathons", 400),
    ("/hackathons", 500),
    ("/hackathons/{id}", 400),
    ("/hackathons/{id}", 404),
}


def validate_responses(schema_path, responses_path):
    api = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
    export = json.loads(responses_path.read_text(encoding="utf-8"))
    if export.get("source") != "CatalogIT HTTP" or not export.get("responses"):
        raise ValueError("Expected a non-empty export of CatalogIT HTTP responses.")

    coverage = set()
    for index, response in enumerate(export["responses"], start=1):
        path = response["operationPath"]
        status = response["status"]
        operation = api["paths"][path]["get"]
        if operation["x-phase"] != "B1" or operation.get("security") != []:
            raise ValueError(f"Response {index} does not belong to a public B1 operation.")
        declared_response = operation["responses"].get(str(status))
        if declared_response is None:
            raise ValueError(f"Response {index}: {path} returned undeclared status {status}.")
        media_type = response["contentType"].split(";", 1)[0].strip().lower()
        content = declared_response["content"]
        if media_type not in content:
            raise ValueError(
                f"Response {index}: {path} {status} returned unexpected Content-Type {media_type!r}."
            )
        schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            **content[media_type]["schema"],
            "components": api["components"],
        }
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(response["body"]), key=lambda error: str(list(error.path)))
        if errors:
            details = "; ".join(f"{list(error.path)}: {error.message}" for error in errors)
            raise ValueError(f"Response {index}: {path} {status}: {details}")
        coverage.add((path, status))

    missing = REQUIRED_COVERAGE - coverage
    if missing:
        raise ValueError(f"HTTP export is missing required coverage: {sorted(missing)}")
    return len(export["responses"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--responses", type=Path, required=True)
    args = parser.parse_args()
    count = validate_responses(args.schema, args.responses)
    print(f"PASS {count} real HTTP responses: OpenAPI status, media type, schemas and formats.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as error:
        sys.exit(f"HTTP contract validation failed: {error}")
