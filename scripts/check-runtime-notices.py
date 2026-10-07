#!/usr/bin/env python3
"""Detect runtime inputs whose bundled notice inventory needs review. No network."""

import hashlib
import json
from pathlib import Path
import sys
import zipfile


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "inference-whisper/src/main/assets/licenses"


def coordinates(item):
    return tuple(item[key] for key in ("group", "name", "version"))


def main():
    if len(sys.argv) not in (2, 3):
        print("Usage: check-runtime-notices.py RESOLVED_RUNTIME_INVENTORY.json [APK]", file=sys.stderr)
        return 2
    actual = json.loads(Path(sys.argv[1]).read_text())
    reviewed = json.loads((ASSETS / "android-runtime-components.json").read_text())
    errors = []
    if actual.get("schemaVersion") != 1 or actual.get("configuration") not in reviewed["configurations"]:
        errors.append("Unsupported runtime inventory schema or configuration.")

    for collection, keys in (
        ("components", ("group", "name", "version")),
        ("artifacts", ("group", "name", "version", "filename", "sha256")),
    ):
        found = [tuple(item[key] for key in keys) for item in actual[collection]]
        expected = [tuple(item[key] for key in keys) for item in reviewed[collection]]
        if (len(found) != len(set(found)) or len(expected) != len(set(expected))
                or set(found) != set(expected)):
            errors.append(f"Resolved {collection} differ from the reviewed notice inventory.")

    components = {coordinates(item): item for item in reviewed["components"]}
    for artifact in reviewed["artifacts"]:
        if coordinates(artifact) not in components:
            errors.append("A reviewed artifact has no component license metadata.")
    if any(item.get("declaredLicense") != "Apache-2.0" or not item.get("licenseMetadata")
           for item in components.values()):
        errors.append("Review the new license and retain its notices before accepting dependencies.")

    text_hashes = set()
    for notice in reviewed["licenseTexts"]:
        name = notice["asset"]
        if Path(name).name != name:
            errors.append("A license text path escapes its asset directory.")
            continue
        path = ASSETS / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != notice["sha256"]:
            errors.append(f"Missing or changed bundled license text: {name}")
        text_hashes.add(notice["sha256"])
    if any(notice["sha256"] not in text_hashes for notice in reviewed["embeddedLicenseFiles"]):
        errors.append("An embedded upstream license has no retained text.")

    if len(sys.argv) == 3:
        with zipfile.ZipFile(sys.argv[2]) as apk:
            names = ["android-runtime-components.json"] + [n["asset"] for n in reviewed["licenseTexts"]]
            for name in names:
                member = f"assets/licenses/{name}"
                if member not in apk.namelist() or apk.read(member) != (ASSETS / name).read_bytes():
                    errors.append(f"Missing or changed packaged runtime notice: {name}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        print("Re-export the graph, review changed artifacts/POMs and update bundled notices.", file=sys.stderr)
        return 1
    print(f"Runtime notices match: {len(components)} components, {len(reviewed['artifacts'])} artifacts.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
