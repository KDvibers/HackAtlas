"""Record and verify the filesystem difference for a pinned image without gosu."""

import hashlib
import json
from pathlib import PurePosixPath
import re
import tarfile


BASE_REFERENCE = (
    "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5"
)
ALPINE_BASE_REFERENCE = (
    "postgres:17.11-alpine3.24@sha256:4717f90960a225c2d1c5dac50508c401230f693ff626e06e84e6db0a68ded508"
)
ALPINE_BASE_IDS = {
    ALPINE_BASE_REFERENCE.split("@")[1],
    "sha256:aac5ed9e090c8b3e134bf1dcf3f9fe0787a29cecdf7867de8b53b5d8abc3eef2",
}
ALPINE_PACKAGES = {"krb5-libs": "1.22.3-r0", "libxslt": "1.1.43-r4", "zlib": "1.4-r0"}
PROOF_FILE = "postgres-no-go.json"
METHOD = "postgres-bookworm-rootfs-diff-v1"
ALPINE_METHOD = "postgres-alpine-rootfs-diff-v2"
GOSU = "usr/local/bin/gosu"
GO_MARKERS = (b"\xff Go buildinf:", b".note.go.buildid")
BINDINGS = ("inspectedImageId", "rehearsedContainerImageId", "configDigest", "archiveSha256", "reportSha256")
SHA256 = re.compile(r"[0-9a-f]{64}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
FILE_KINDS = {"file", "elf", "go-elf"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def safe_path(value):
    path = PurePosixPath(value)
    require(not path.is_absolute() and ".." not in path.parts, "Unsafe path in PostgreSQL filesystem export")
    return str(path)


def fingerprint(stream):
    head = stream.read(4)
    elf = head == b"\x7fELF"
    digest = hashlib.sha256(head)
    tail = head
    go = False
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        if elf and not go:
            window = tail + chunk
            go = any(marker in window for marker in GO_MARKERS)
            tail = window[-32:]
    return ("go-elf" if go else "elf" if elf else "file"), digest.hexdigest()


def inventory(export):
    files = {}
    with tarfile.open(export, mode="r|*") as archive:
        for member in archive:
            path = safe_path(member.name)
            require(path not in files, "Duplicate path in PostgreSQL filesystem export")
            if member.isfile():
                with archive.extractfile(member) as stream:
                    kind, content = fingerprint(stream)
            elif member.isdir():
                kind, content = "dir", ""
            elif member.issym() or member.islnk():
                kind = "symlink" if member.issym() else "hardlink"
                content = member.linkname
            else:
                raise ValueError("Unsupported entry in PostgreSQL filesystem export")
            files[path] = [kind, member.mode, member.uid, member.gid, content]
    return files


def validate_inventory(files):
    require(isinstance(files, dict) and files, "Empty PostgreSQL filesystem inventory")
    for path, entry in files.items():
        require(isinstance(path, str) and path and safe_path(path) == path, "Invalid inventory path")
        require(isinstance(entry, list) and len(entry) == 5, "Invalid filesystem fingerprint")
        kind, mode, uid, gid, content = entry
        require(all(type(value) is int and value >= 0 for value in (mode, uid, gid))
                and isinstance(content, str) and isinstance(kind, str), "Invalid filesystem fingerprint")
        if kind in FILE_KINDS:
            require(matches(SHA256, content), "Invalid file hash in filesystem inventory")
        else:
            require((kind == "dir" and not content) or (kind in {"symlink", "hardlink"} and content),
                    "Invalid non-file fingerprint")


def validate_proof(proof, image):
    require(isinstance(proof, dict) and type(proof.get("formatVersion")) is int, "Unsupported PostgreSQL proof")
    version = (proof["formatVersion"], proof.get("method"))
    require(version in ((1, METHOD), (2, ALPINE_METHOD)), "Unsupported PostgreSQL proof")
    alpine = version == (2, ALPINE_METHOD)
    reference, user = (ALPINE_BASE_REFERENCE, "70:70") if alpine else (BASE_REFERENCE, "999:999")
    require(proof.get("baseReference") == reference and matches(IMAGE_ID, proof.get("baseImageId")),
            "Unexpected PostgreSQL proof base")
    require(proof.get("candidateUser") == user, "Unexpected PostgreSQL candidate user")
    binding = proof.get("candidate")
    require(isinstance(binding, dict) and set(binding) == set(BINDINGS), "Incomplete PostgreSQL proof identity")
    require(all(matches(IMAGE_ID if key.endswith(("Id", "Digest")) else SHA256, binding[key])
                and binding[key] == image.get(key) for key in BINDINGS), "PostgreSQL proof does not match the audited image")
    require(binding["rehearsedContainerImageId"] in (binding["inspectedImageId"], binding["configDigest"]),
            "PostgreSQL proof does not match the rehearsal container")
    base_layers, candidate_layers = proof.get("baseLayers"), proof.get("candidateLayers")
    require(isinstance(base_layers, list) and base_layers and isinstance(candidate_layers, list)
            and all(matches(IMAGE_ID, digest) for digest in candidate_layers)
            and candidate_layers[:-1] == base_layers, "PostgreSQL image must add one layer to the pinned base")
    if alpine:
        require(proof.get("officialBaseImageId") in ALPINE_BASE_IDS, "Unexpected official Alpine image")
        require(proof["baseImageId"] == image.get("packagesImageId"), "Unexpected Alpine package image")
        official_layers = proof.get("officialBaseLayers")
        require(isinstance(official_layers, list) and official_layers and base_layers[:-1] == official_layers,
                "Unexpected Alpine package layer")
    base, candidate = proof.get("baseFiles"), proof.get("candidateFiles")
    validate_inventory(base)
    validate_inventory(candidate)
    require([path for path, entry in base.items() if entry[0] == "go-elf"] == [GOSU],
            "Pinned PostgreSQL base must contain only gosu as Go ELF")
    require(any(entry[0] == "elf" for entry in candidate.values()), "Candidate ELF inventory is empty")
    require(candidate.keys() == base.keys() - {GOSU} and all(base[path] == entry for path, entry in candidate.items()),
            "PostgreSQL filesystem difference is more than removing gosu")
    return "alpine" if alpine else "debian"


def export_inventory(run, image_id, expected_container_image_id, cache, export):
    container = run(["docker", "create", "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt",
                     "no-new-privileges", "--tmpfs", "/var/lib/postgresql/data",
                     "--label", "hackatlas.audit=" + cache, image_id]).decode().strip()
    require(re.fullmatch(r"[0-9a-f]{64}", container), "Unexpected PostgreSQL export container ID")
    try:
        inspected = json.loads(run(["docker", "inspect", container]))[0]
        require(inspected["Config"].get("Labels", {}).get("hackatlas.audit") == cache,
                "PostgreSQL export container is not owned by this audit")
        require(matches(IMAGE_ID, inspected.get("Image")) and
                (expected_container_image_id is None or inspected["Image"] == expected_container_image_id),
                "Exported PostgreSQL container does not match the rehearsal image")
        run(["docker", "export", "--output", str(export), container])
        return inventory(export)
    finally:
        inspected = json.loads(run(["docker", "inspect", container]))[0]
        require(inspected["Config"].get("Labels", {}).get("hackatlas.audit") == cache,
                "Refusing cleanup of an unowned PostgreSQL export container")
        run(["docker", "rm", "--volumes", container])
        export.unlink(missing_ok=True)


def produce_proof(run, candidate, image, cache, directory, *, packages=None):
    alpine = packages is not None
    reference, user = (ALPINE_BASE_REFERENCE, "70:70") if alpine else (BASE_REFERENCE, "999:999")
    official = json.loads(run(["docker", "image", "inspect", reference]))[0]
    base = packages if alpine else official
    require(base["Os"] == candidate["Os"] == "linux" and base["Architecture"] == candidate["Architecture"],
            "Unexpected PostgreSQL candidate platform")
    require(candidate["Config"] == {**base["Config"], "User": user}, "Unexpected PostgreSQL image configuration")
    if alpine:
        require(official["Id"] in ALPINE_BASE_IDS and official["Os"] == "linux"
                and official["Architecture"] == base["Architecture"] == "amd64", "Unexpected official Alpine image")
        require(base["Config"] == official["Config"]
                and base["RootFS"]["Layers"][:-1] == official["RootFS"]["Layers"], "Unexpected Alpine package layer")
    proof = {"formatVersion": 2 if alpine else 1, "method": ALPINE_METHOD if alpine else METHOD,
             "baseReference": reference, "baseImageId": base["Id"],
             "baseLayers": base["RootFS"]["Layers"], "candidateLayers": candidate["RootFS"]["Layers"],
             "candidateUser": candidate["Config"]["User"], "candidate": {key: image[key] for key in BINDINGS},
             "baseFiles": export_inventory(run, base["Id"], None, cache, directory / "base-rootfs.tar"),
             "candidateFiles": export_inventory(run, candidate["Id"], image["rehearsedContainerImageId"],
                                                cache, directory / "candidate-rootfs.tar")}
    if alpine:
        proof.update(officialBaseImageId=official["Id"], officialBaseLayers=official["RootFS"]["Layers"])
    validate_proof(proof, image)
    return json.dumps(proof, sort_keys=True, separators=(",", ":")).encode()
