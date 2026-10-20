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
psy_assets = root / 'inference-transcribepsy/src/main/assets'
psy_models = json.loads((psy_assets / 'transcribepsy-models.json').read_text())['models']
models = models + psy_models
assert len(models) == len({model['id'] for model in models})
assert len(models) == len({model['filename'] for model in models})
assert {'whisper-base-multilingual', 'whisper-small-q8', 'whisper-small-fp16'} <= {model['id'] for model in models}
cmake = (root / 'inference-whisper/src/main/cpp/CMakeLists.txt').read_text()
assert re.search(r'URL_HASH SHA256=[0-9a-f]{64}', cmake)
required = {'id', 'displayName', 'runtimeFamily', 'runtimeVersion', 'runtimeRevision',
            'modelRevision', 'filename', 'sourceUrl', 'bytes', 'sha256', 'languageScope',
            'license', 'attribution', 'licenseUrl', 'description', 'experimental'}
for model in models:
    psy = model.get('engine') == 'TRANSCRIBEPSY'
    assert set(model) == required | ({'engine'} if psy else set())
    assert model['runtimeFamily'] == ('transcribepsy' if psy else 'whisper.cpp')
    assert model['languageScope'] in {'multilingual', 'Spanish fine-tune', '8-language multilingual'}
    assert re.fullmatch(r'[a-z0-9-]+', model['id'])
    assert re.fullmatch(r'transcribepsy-[a-z0-9_.-]+\.gguf' if psy else r'ggml-[a-z0-9_.-]+\.bin', model['filename'])
    assert re.fullmatch(r'[0-9a-f]{64}', model['sha256'])
    for field in ['runtimeRevision', 'modelRevision']:
        assert re.fullmatch(r'[0-9a-f]{40}', model[field])
    runtime_cmake = (root / 'inference-transcribepsy/src/main/cpp/CMakeLists.txt').read_text() if psy else cmake
    assert model['runtimeRevision'] in runtime_cmake
    assert psy or model['runtimeRevision'] == legacy['runtimeRevision']
    assert 0 < model['bytes'] <= 1_073_741_824
    assert model['license'] in {'MIT', 'Apache-2.0'} and model['attribution']
    assert isinstance(model['experimental'], bool) and model['displayName'] and model['description']
    for field in ['sourceUrl', 'licenseUrl']:
        url = urlparse(model[field])
        assert url.scheme == 'https' and url.hostname and not url.username and not url.fragment
    assert model['modelRevision'] in model['sourceUrl']
    if model['experimental'] and not psy:
        assert model['license'] == 'Apache-2.0' and 'Chilean' in model['displayName']
base = next(model for model in models if model['id'] == legacy['id'])
assert all(base[field] == legacy[field] for field in ['filename', 'bytes', 'sha256', 'sourceUrl'])
preparation = json.loads((psy_assets / 'transcribepsy-model-preparation.json').read_text())
assert preparation['schemaVersion'] == 1
assert preparation['runtimeArchiveSha256'] in (root / 'inference-transcribepsy/src/main/cpp/CMakeLists.txt').read_text()
for output in preparation['outputs']:
    profile = next(model for model in psy_models if model['id'] == output['id'])
    assert all(output[key] == profile[key] for key in ['filename', 'bytes', 'sha256'])
    assert profile['runtimeRevision'] == preparation['runtimeRevision']
assert {notice['asset'] for notice in preparation['licenseTexts']} == {
    'TranscribePsy-NOTICE.txt', 'TranscribePsy-dr_wav-MIT-0.txt',
    'TranscribePsy-ggml-MIT.txt', 'TranscribePsy-runtime-Apache-2.0.txt',
    'OpenCL-Headers-Apache-2.0.txt',
}
assert len(preparation['licenseTexts']) == 5
opencl = preparation['openclHeaders']
assert opencl['license'] == 'Apache-2.0'
assert opencl['version'] == 'v2024.10.24'
assert opencl['archiveSha256'] in runtime_cmake
assert opencl['version'] in runtime_cmake
for notice in preparation['licenseTexts']:
    assert Path(notice['asset']).name == notice['asset']
    assert hashlib.sha256((psy_assets / 'licenses' / notice['asset']).read_bytes()).hexdigest() == notice['sha256']
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
