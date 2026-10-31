"""Acquire a fixed model revision and verify every file before installation."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent

REPO = "dropbox-dash/faster-whisper-large-v3-turbo"
REVISION = "0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf"
WEIGHT_SHA256 = "e76620f83d5f5b69efd3d87e3dc180c1bd21df9fbebacfd4335e5e1efcc018da"
DESTINATION = ROOT / "models" / "large-v3-turbo"
FILES = {
    "model.bin": (1617884929, WEIGHT_SHA256),
    "config.json": (2263, "b0253ea6c0d3bea6b1e19e91a02acfd3b53f4467362efcb5a3e6b16c9b3a9b7e"),
    "preprocessor_config.json": (340, "7ccc62c6f2765af1f3b46c00c9b5894426835a05021c8b9c01eecb6dfb542711"),
    "tokenizer.json": (2710337, "297b13372ac43916285644fb9687add3cc62ee2a1adb60da3dc25cc94c1871fd"),
    "vocabulary.json": (1068114, "c69260f2ab26d659b7c398f9a2b2b48ed0df16c3b47d7326782fd9cba71690c1"),
    "README.md": (1445, "b3068692728faed23580cce5cd569fc47ff76c690c032b2641ffd5554ea64d8f"),
}


def matches(path, size, expected):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() == expected


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name, (size, expected) in FILES.items():
        target = DESTINATION / name
        if matches(target, size, expected):
            continue
        temporary = target.with_suffix(target.suffix + ".download")
        try:
            digest = hashlib.sha256()
            received = 0
            with urlopen(f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}", timeout=120) as source, temporary.open("wb") as destination:
                while chunk := source.read(1024 * 1024):
                    received += len(chunk)
                    if received > size:
                        raise RuntimeError("Model file exceeds pinned size: " + name)
                    digest.update(chunk)
                    destination.write(chunk)
            if received != size or digest.hexdigest() != expected:
                raise RuntimeError("Model size/checksum mismatch: " + name)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    (DESTINATION / "verified.json").write_text(
        json.dumps({"repository": REPO, "revision": REVISION, "weight_sha256": WEIGHT_SHA256}, indent=2),
        encoding="utf-8",
    )
    print("Model files verified:", DESTINATION, flush=True)


if __name__ == "__main__":
    main()
