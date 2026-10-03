#!/usr/bin/env python3
"""Check release metadata without downloading a model during every verification."""
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / "inference-whisper/src/main/assets/whisper-model.json").read_text())
required = {
    "schemaVersion", "id", "runtimeFamily", "runtimeVersion", "runtimeRevision",
    "modelRevision", "filename", "sourceUrl", "bytes", "sha256", "languageScope",
    "license", "attribution", "licenseUrl",
}
assert set(manifest) == required and manifest["schemaVersion"] == 1
assert manifest["runtimeFamily"] == "whisper.cpp" and manifest["languageScope"] == "multilingual"
assert re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"])
for field in ["runtimeRevision", "modelRevision"]:
    assert re.fullmatch(r"[0-9a-f]{40}", manifest[field])
assert 0 < manifest["bytes"] <= 500_000_000
assert manifest["filename"] == "ggml-base.bin"
assert manifest["license"] == "MIT" and manifest["attribution"]
for field in ["sourceUrl", "licenseUrl"]:
    url = urlparse(manifest[field])
    assert url.scheme == "https" and url.hostname and not url.username and not url.fragment
assert manifest["modelRevision"] in manifest["sourceUrl"]
cmake = (root / "inference-whisper/src/main/cpp/CMakeLists.txt").read_text()
assert manifest["runtimeRevision"] in cmake
assert re.search(r"URL_HASH SHA256=[0-9a-f]{64}", cmake)
if len(sys.argv) > 1:
    file = Path(sys.argv[1])
    assert file.stat().st_size == manifest["bytes"]
    with file.open("rb") as source:
        assert hashlib.file_digest(source, "sha256").hexdigest() == manifest["sha256"]
print("Model manifest valid" + ("; artifact verified" if len(sys.argv) > 1 else ""))
