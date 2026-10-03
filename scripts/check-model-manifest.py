#!/usr/bin/env python3
"""Check allowlisted release metadata; optionally verify any named model artifact."""
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parent.parent
assets = root / 'inference-whisper/src/main/assets'
legacy = json.loads((assets / 'whisper-model.json').read_text())
catalog = json.loads((assets / 'whisper-models.json').read_text())
assert set(catalog) == {'schemaVersion', 'models'} and catalog['schemaVersion'] == 1
models = catalog['models']
assert len(models) == len({model['id'] for model in models})
assert len(models) == len({model['filename'] for model in models})
assert {'whisper-base-multilingual', 'whisper-small-q8', 'whisper-small-fp16'} <= {model['id'] for model in models}
cmake = (root / 'inference-whisper/src/main/cpp/CMakeLists.txt').read_text()
assert re.search(r'URL_HASH SHA256=[0-9a-f]{64}', cmake)
required = {'id', 'displayName', 'runtimeFamily', 'runtimeVersion', 'runtimeRevision',
            'modelRevision', 'filename', 'sourceUrl', 'bytes', 'sha256', 'languageScope',
            'license', 'attribution', 'licenseUrl', 'description', 'experimental'}
for model in models:
    assert set(model) == required
    assert model['runtimeFamily'] == 'whisper.cpp'
    assert model['languageScope'] in {'multilingual', 'Spanish fine-tune'}
    assert re.fullmatch(r'[a-z0-9-]+', model['id'])
    assert re.fullmatch(r'ggml-[a-z0-9_.-]+\.bin', model['filename'])
    assert re.fullmatch(r'[0-9a-f]{64}', model['sha256'])
    for field in ['runtimeRevision', 'modelRevision']:
        assert re.fullmatch(r'[0-9a-f]{40}', model[field])
    assert model['runtimeRevision'] in cmake and model['runtimeRevision'] == legacy['runtimeRevision']
    assert 0 < model['bytes'] <= 500_000_000
    assert model['license'] in {'MIT', 'Apache-2.0'} and model['attribution']
    assert isinstance(model['experimental'], bool) and model['displayName'] and model['description']
    for field in ['sourceUrl', 'licenseUrl']:
        url = urlparse(model[field])
        assert url.scheme == 'https' and url.hostname and not url.username and not url.fragment
    assert model['modelRevision'] in model['sourceUrl']
    if model['experimental']:
        assert model['license'] == 'Apache-2.0' and 'Chilean' in model['displayName']
base = next(model for model in models if model['id'] == legacy['id'])
assert all(base[field] == legacy[field] for field in ['filename', 'bytes', 'sha256', 'sourceUrl'])
for argument in sys.argv[1:]:
    file = Path(argument)
    model = next((model for model in models if model['filename'] == file.name), None)
    # Preserve the original base smoke command's revision-suffixed operator filename.
    if model is None and len(sys.argv) == 2 and file.stat().st_size == legacy['bytes']:
        model = legacy
    assert model is not None, 'Artifact filename is not in the catalog'
    assert file.stat().st_size == model['bytes']
    with file.open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == model['sha256']
print(f'Model catalog valid ({len(models)} profiles)' + ('; artifacts verified' if len(sys.argv) > 1 else ''))
