#!/usr/bin/env python3
"""Prepare pinned Small models outside Git; optionally convert the Chilean fine-tune."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = '927cfce34f31707e17f2bff35c349632fb9e2c3a'
ARCHIVE_SHA = '41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde'
STOCK_REV = '5359861c739e955e79d9a303bcbc70fb988958b1'
CHILE_REV = '57e689bd5edc1ae84e7c8683235178a3c4505cea'
OPENAI_REV = '31243bad24cc746f07d4c8bfdd2d974872cb1803'
STOCK = [
    ('ggml-small.bin', 487601967, '1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b'),
    ('ggml-small-q8_0.bin', 264464607, '49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f'),
]


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def download(url, destination, expected_sha=None, expected_bytes=None):
    if destination.is_file() and (expected_sha is None or sha(destination) == expected_sha):
        if expected_bytes is None or destination.stat().st_size == expected_bytes:
            return
    partial = destination.with_suffix(destination.suffix + '.part')
    try:
        with urllib.request.urlopen(url, timeout=120) as source, partial.open('wb') as output:
            shutil.copyfileobj(source, output)
        if expected_bytes is not None and partial.stat().st_size != expected_bytes:
            raise ValueError('Unexpected download size: ' + destination.name)
        if expected_sha is not None and sha(partial) != expected_sha:
            raise ValueError('Download hash mismatch: ' + destination.name)
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--chilean', action='store_true', help='Convert ES-CL-2 to FP16 and Q8_0; needs uv, CMake, and a C++ compiler.')
    parser.add_argument('--regenerate-q8', action='store_true', help='Generate stock Q8 from FP16 instead of downloading it; assert the published hash.')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(ROOT):
        parser.error('Model output must be outside the Git checkout.')
    output.mkdir(parents=True, exist_ok=True)
    def stock(item):
        name, size, digest = item
        download(f'https://huggingface.co/ggerganov/whisper.cpp/resolve/{STOCK_REV}/{name}', output / name, digest, size)
        print('Verified ' + name, flush=True)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        list(pool.map(stock, STOCK[:1] if args.regenerate_q8 else STOCK))
    download(f'https://raw.githubusercontent.com/openai/whisper/{OPENAI_REV}/LICENSE', output / 'Whisper-MIT.txt')
    recipe = {'runtimeRevision': RUNTIME, 'stockRevision': STOCK_REV, 'openaiRevision': OPENAI_REV}
    if args.chilean or args.regenerate_q8:
        with tempfile.TemporaryDirectory(prefix='.altiro-preparation-', dir=output) as directory:
            work = Path(directory)
            archive = work / 'whisper.tar.gz'
            download(f'https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/{RUNTIME}', archive, ARCHIVE_SHA)
            with tarfile.open(archive) as bundle:
                bundle.extractall(work, filter='data')
            runtime = work / ('whisper.cpp-' + RUNTIME)
            build = work / 'build'
            subprocess.run(['cmake', '-S', str(runtime), '-B', str(build), '-DCMAKE_BUILD_TYPE=Release',
                            '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', '-DGGML_NATIVE=OFF', '-DGGML_OPENMP=OFF',
                            '-DGGML_METAL=OFF', '-DGGML_CUDA=OFF', '-DGGML_VULKAN=OFF',
                            '-DGGML_CPU_KLEIDIAI=OFF', '-DWHISPER_BUILD_TESTS=OFF', '-DBUILD_SHARED_LIBS=OFF'], check=True)
            subprocess.run(['cmake', '--build', str(build), '--target', 'whisper-quantize', 'whisper-cli', '--parallel', '2'], check=True)
            quantize = build / 'bin/whisper-quantize'
            if args.regenerate_q8:
                generated = work / STOCK[1][0]
                subprocess.run([str(quantize), str(output / STOCK[0][0]), str(generated), 'q8_0'], check=True)
                assert generated.stat().st_size == STOCK[1][1] and sha(generated) == STOCK[1][2]
                generated.replace(output / STOCK[1][0])
                print('Regenerated stock Q8 matches published SHA-256', flush=True)
            if args.chilean:
                source = work / 'chilean'; source.mkdir()
                repo = 'rcastrovexler/whisper-small-es-cl-2'
                with urllib.request.urlopen(f'https://huggingface.co/api/models/{repo}/revision/{CHILE_REV}?blobs=true') as response:
                    metadata = json.load(response)
                wanted = {'model.safetensors', 'config.json', 'vocab.json', 'added_tokens.json', 'tokenizer_config.json',
                          'tokenizer.json', 'merges.txt', 'generation_config.json', 'preprocessor_config.json',
                          'special_tokens_map.json', 'normalizer.json', 'README.md'}
                inputs = {}
                for item in metadata['siblings']:
                    name = item['rfilename']
                    if name not in wanted: continue
                    destination = source / name
                    digest = item.get('lfs', {}).get('sha256')
                    if name == 'model.safetensors':
                        assert digest == 'efd12759bcf5e1ddcbcc0d20ca059aa5fe1e4071f292987d8fcdad3000c9fe74'
                    download(f'https://huggingface.co/{repo}/resolve/{CHILE_REV}/{name}', destination, digest, item.get('size'))
                    inputs[name] = {'bytes': destination.stat().st_size, 'sha256': sha(destination)}
                assert {'model.safetensors', 'config.json', 'vocab.json', 'added_tokens.json'} <= inputs.keys()
                assets = work / 'openai/whisper/assets'; assets.mkdir(parents=True)
                download(f'https://raw.githubusercontent.com/openai/whisper/{OPENAI_REV}/whisper/assets/mel_filters.npz', assets / 'mel_filters.npz', '7450ae70723a5ef9d341e3cee628c7cb0177f36ce42c44b7ed2bf3325f0f6d4c')
                env = os.environ | {'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1', 'UV_CACHE_DIR': str(work / 'uv-cache')}
                subprocess.run(['uv', 'run', '--no-project', '--with', 'torch==2.9.1', '--with', 'transformers==4.48.3',
                                '--with', 'numpy==2.2.6', 'python', str(runtime / 'models/convert-h5-to-ggml.py'),
                                str(source), str(work / 'openai'), str(work)], env=env, check=True)
                fp16 = work / 'ggml-small-es-cl-2-f16.bin'
                (work / 'ggml-model.bin').replace(fp16)
                subprocess.run(['uv', 'run', '--no-project', '--with', 'numpy==2.2.6', '--with', 'safetensors==0.5.3',
                                'python', str(ROOT / 'scripts/check-converted-whisper.py'),
                                str(source / 'model.safetensors'), str(fp16)], env=env, check=True)
                q8 = work / 'ggml-small-es-cl-2-q8_0.bin'
                subprocess.run([str(quantize), str(fp16), str(q8), 'q8_0'], check=True)
                shutil.copyfile(source / 'README.md', output / 'Chilean-model-card.md')
                download('https://www.apache.org/licenses/LICENSE-2.0.txt', output / 'Chilean-Apache-2.0.txt')
                recipe['chilean'] = {'repo': repo, 'revision': CHILE_REV, 'inputs': inputs,
                                     'converter': 'models/convert-h5-to-ggml.py',
                                     'melFiltersSha256': sha(assets / 'mel_filters.npz'),
                                     'pythonDependencies': ['torch==2.9.1', 'transformers==4.48.3', 'numpy==2.2.6'],
                                     'auditDependencies': ['numpy==2.2.6', 'safetensors==0.5.3']}
                # Actual runtime loading and recognition, using a public canonical sample.
                sample = runtime / 'samples/jfk.wav'
                for model in [output / STOCK[0][0], output / STOCK[1][0], fp16, q8]:
                    subprocess.run([str(build / 'bin/whisper-cli'), '-m', str(model), '-f', str(sample), '-l', 'en',
                                    '-t', '4', '-bs', '1', '-bo', '1', '-nf', '-nt', '-ng', '-otxt', '-of', str(work / 'smoke')], check=True,
                                   stdout=subprocess.DEVNULL)
                    text = (work / 'smoke.txt').read_text()
                    assert 'country' in text.lower(), 'Public sample recognition failed: ' + model.name
                    print('Runtime smoke passed: ' + model.name, flush=True)
                catalog = json.loads((ROOT / 'inference-whisper/src/main/assets/whisper-models.json').read_text())
                for generated in [fp16, q8]:
                    profile = next(model for model in catalog['models'] if model['filename'] == generated.name)
                    assert generated.stat().st_size == profile['bytes'] and sha(generated) == profile['sha256']
                    generated.replace(output / generated.name)
    recipe['artifacts'] = {path.name: {'bytes': path.stat().st_size, 'sha256': sha(path)} for path in output.glob('ggml-*.bin')}
    (output / 'preparation.json').write_text(json.dumps(recipe, indent=2) + '\n')
    print('Preparation complete. Keep preparation.json and license notices with the model files.', flush=True)


if __name__ == '__main__':
    main()
