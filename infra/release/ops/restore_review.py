"""Private operator review and atomic approval of a quarantined restore."""

from datetime import datetime, timedelta, timezone
import json
import os
import re
import stat
import uuid

STATE_KEYS = {"kind", "formatVersion", "project", "database", "backupSha256", "restoreId", "state", "restoredAt"}
REVIEW_KEYS = {"formatVersion", "project", "database", "backupSha256", "restoreId", "sourceWritesStopped",
              "deletionsReviewed", "reviewedThrough", "deletedAccountIds"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def target_database(value):
    require(isinstance(value, str) and re.fullmatch(r"hackatlas_restore_[a-z0-9_]{1,40}", value),
            "Only a new hackatlas_restore_<unique> database can be reviewed.")
    return value


def timestamp(value):
    try:
        require(isinstance(value, str), "Timestamp must be text.")
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(result.utcoffset() is not None, "Timestamp requires a timezone.")
        return result.astimezone(timezone.utc)
    except (ValueError, TypeError):
        raise ValueError("Review/restore requires a valid timezone-aware timestamp.") from None


def account_ids(values):
    require(isinstance(values, list) and len(values) <= 10000, "Review requires a list of at most 10000 account UUIDs.")
    result = []
    for value in values:
        try:
            require(isinstance(value, str), "Account UUID must be text.")
            identifier = str(uuid.UUID(value))
            require(identifier == value.lower(), "Account UUID must use canonical format.")
        except (ValueError, TypeError, AttributeError):
            raise ValueError("Review contains an invalid account UUID.") from None
        result.append(identifier)
    require(len(result) == len(set(result)), "Review contains duplicate account UUIDs.")
    return result


def validate_state(state):
    require(isinstance(state, dict) and set(state) == STATE_KEYS, "Unsupported restore metadata.")
    require(state["kind"] == "hackatlas.restore" and type(state["formatVersion"]) is int
            and state["formatVersion"] == 1, "Unsupported restore metadata format.")
    require(state["state"] == "QUARANTINED", "Restore is not awaiting review; repeated approval is refused.")
    target_database(state["database"])
    require(isinstance(state["project"], str) and re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", state["project"]),
            "Invalid restore project identity.")
    require(isinstance(state["backupSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", state["backupSha256"]),
            "Invalid restore backup identity.")
    account_ids([state["restoreId"]])
    return timestamp(state["restoredAt"])


def quarantine_state(project, database, checksum, *, now):
    state = {"kind": "hackatlas.restore", "formatVersion": 1, "project": project, "database": database,
             "backupSha256": checksum, "restoreId": str(uuid.uuid4()), "state": "QUARANTINED",
             "restoredAt": now.astimezone(timezone.utc).isoformat()}
    validate_state(state)
    return state


def validate_review(review, state, *, now):
    restored = validate_state(state)
    require(isinstance(review, dict) and set(review) == REVIEW_KEYS, "Review fields are missing or unknown.")
    require(type(review["formatVersion"]) is int and review["formatVersion"] == 1, "Unsupported review format.")
    for key in ("project", "database", "backupSha256", "restoreId"):
        require(review[key] == state[key], "Review does not match this project/database/backup/restore attempt.")
    require(review["sourceWritesStopped"] is True and review["deletionsReviewed"] is True,
            "Operator must confirm stopped source writers and a complete independent deletion review.")
    reviewed = timestamp(review["reviewedThrough"])
    require(restored <= reviewed <= now + timedelta(minutes=5) and now - reviewed <= timedelta(hours=24),
            "Review must cover this restore, be recent and not future-dated.")
    return account_ids(review["deletedAccountIds"])


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Review JSON contains duplicate keys.")
        result[key] = value
    return result


def read_private_review(path):
    require(os.name == "posix", "Private review files require Linux/WSL permissions.")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        raise ValueError("Review must be a readable regular file, not a symlink.") from None
    with os.fdopen(descriptor, "rb") as source:
        attributes = os.fstat(source.fileno())
        require(stat.S_ISREG(attributes.st_mode) and attributes.st_mode & 0o077 == 0,
                "Review must be a private regular file (mode 0600 or stricter).")
        require(attributes.st_size <= 1024 * 1024, "Review file exceeds 1 MiB.")
        raw = source.read(1024 * 1024 + 1)
    require(len(raw) <= 1024 * 1024, "Review file exceeds 1 MiB.")
    try:
        return json.loads(raw, object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Review file is not valid JSON.") from None


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def approval_sql(database, state, ids, reviewed_through):
    target_database(database)
    validate_state(state)
    require(database == state["database"], "Approval must target the recorded restore database.")
    ids = account_ids(ids)
    reviewed = timestamp(reviewed_through).isoformat()
    expected = literal(json.dumps(state, sort_keys=True, separators=(",", ":")))
    array = "ARRAY[" + ",".join(literal(value) + "::uuid" for value in ids) + "]::uuid[]"
    # All embedded values have restrictive validation; identifiers never come from free text.
    return f"""BEGIN;
DO $hackatlas_review$
DECLARE expected jsonb := {expected}::jsonb; actual jsonb; removed bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('hackatlas.restore:' || current_database(), 0));
  IF current_database() <> expected->>'database' THEN RAISE EXCEPTION 'Wrong restore database'; END IF;
  SELECT shobj_description(oid, 'pg_database')::jsonb INTO actual
    FROM pg_database WHERE datname = current_database();
  IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'Restore state changed'; END IF;
  IF has_database_privilege('hackatlas_runtime', current_database(), 'CONNECT') OR EXISTS (
    SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename = 'hackatlas_runtime'
  ) THEN RAISE EXCEPTION 'Restore runtime must remain disconnected'; END IF;
  WITH gone AS (
    DELETE FROM hackatlas.user_account WHERE id = ANY({array}) RETURNING 1
  ), audit AS (
    INSERT INTO hackatlas.admin_audit (id, action, created_at)
      SELECT gen_random_uuid(), 'ACCOUNT_DELETED', clock_timestamp() FROM gone RETURNING 1
  ) SELECT count(*) INTO removed FROM audit;
  DELETE FROM hackatlas.auth_session;
  actual := expected || jsonb_build_object('state', 'APPROVED_FOR_SMOKE', 'approvedAt', clock_timestamp(),
    'reviewedThrough', {literal(reviewed)}, 'reviewedAccountIds', {len(ids)}, 'deletedAccounts', removed);
  EXECUTE format('COMMENT ON DATABASE %I IS %L', current_database(), actual::text);
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO hackatlas_runtime', current_database());
END;
$hackatlas_review$;
COMMIT;
SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database();
"""
