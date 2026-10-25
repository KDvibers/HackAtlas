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

B2_REQUIRED_COVERAGE = {
    ("/auth/register", "post", 201),
    ("/auth/register", "post", 400),
    ("/auth/register", "post", 403),
    ("/auth/register", "post", 409),
    ("/auth/register", "post", 429),
    ("/auth/login", "post", 200),
    ("/auth/login", "post", 400),
    ("/auth/login", "post", 401),
    ("/auth/login", "post", 429),
    ("/auth/logout", "post", 204),
    ("/auth/logout", "post", 401),
    ("/me", "get", 200),
    ("/me", "get", 401),
    ("/me", "patch", 200),
    ("/me", "patch", 400),
    ("/me", "patch", 401),
    ("/me/saved", "get", 200),
    ("/me/saved", "get", 400),
    ("/me/saved", "get", 401),
    ("/me/saved/{id}", "put", 204),
    ("/me/saved/{id}", "put", 400),
    ("/me/saved/{id}", "put", 401),
    ("/me/saved/{id}", "put", 404),
    ("/me/saved/{id}", "delete", 204),
    ("/me/saved/{id}", "delete", 400),
    ("/me/saved/{id}", "delete", 401),
    ("/me/applications", "get", 200),
    ("/me/applications", "get", 400),
    ("/me/applications", "get", 401),
    ("/me/applications/{id}", "put", 200),
    ("/me/applications/{id}", "put", 400),
    ("/me/applications/{id}", "put", 401),
    ("/me/applications/{id}", "put", 404),
    ("/me/applications/{id}", "delete", 204),
    ("/me/applications/{id}", "delete", 400),
    ("/me/applications/{id}", "delete", 401),
}


def validate_responses(schema_path, responses_path):
    api = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
    sources = sorted(responses_path.glob("*.json")) if responses_path.is_dir() else [responses_path]
    if not sources:
        raise ValueError("No HTTP response exports found.")
    exports = [json.loads(path.read_text(encoding="utf-8")) for path in sources]
    source_types = {export.get("source") for export in exports}
    if source_types == {"CatalogIT HTTP"}:
        phase = "B1"
    elif source_types == {"B2 HTTP"}:
        phase = "B2"
    else:
        raise ValueError("Expected separate non-empty exports of CatalogIT HTTP or B2 HTTP responses.")
    responses = [response for export in exports for response in export.get("responses", [])]
    if not responses or any(not export.get("responses") for export in exports):
        raise ValueError("Every HTTP export must contain actual responses.")

    coverage = set()
    for index, response in enumerate(responses, start=1):
        path = response["operationPath"]
        method = response.get("method", "get")
        status = response["status"]
        operation = api["paths"][path][method]
        if operation["x-phase"] != phase:
            raise ValueError(f"Response {index} does not belong to a {phase} operation.")
        if phase == "B1" and operation.get("security") != []:
            raise ValueError(f"Response {index} does not belong to a public B1 operation.")
        declared_response = operation["responses"].get(str(status))
        if declared_response is None:
            raise ValueError(f"Response {index}: {path} returned undeclared status {status}.")
        coverage.add((path, status) if phase == "B1" else (path, method, status))
        if status == 204:
            if declared_response.get("content") or response.get("body") is not None:
                raise ValueError(f"Response {index}: {path} 204 must have no response body.")
            continue
        if phase == "B2" and path in {"/auth/register", "/auth/login"} and status in {200, 201}:
            if "no-store" not in response.get("cacheControl", "").lower():
                raise ValueError(f"Response {index}: token response must use Cache-Control: no-store.")
            if response["body"].get("accessToken") != "x" * 43:
                raise ValueError(f"Response {index}: exported token was not redacted.")
        if status == 429 and not response.get("retryAfter", "").isdigit():
            raise ValueError(f"Response {index}: Retry-After must be a delay in seconds.")
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

    missing = (REQUIRED_COVERAGE if phase == "B1" else B2_REQUIRED_COVERAGE) - coverage
    if missing:
        raise ValueError(f"HTTP export is missing required coverage: {sorted(missing)}")
    return len(responses)


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
