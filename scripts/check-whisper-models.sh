#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
if [ "$#" -ne 2 ]; then
  echo 'Usage: check-whisper-models.sh PREPARED_MODEL_DIRECTORY CANONICAL_JFK_WAV' >&2
  exit 2
fi
models=$1
speech=$2
set -- "$models/ggml-small-q8_0.bin" "$models/ggml-small.bin" \
  "$models/ggml-small-es-cl-2-q8_0.bin" "$models/ggml-small-es-cl-2-f16.bin"
python3 scripts/check-model-manifest.py "$@"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/altiro-model-jni-XXXXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
cmake -S inference-whisper/src/main/cpp -B "$scratch/native" -DCMAKE_BUILD_TYPE=Release
cmake --build "$scratch/native" --parallel 2
javac -d "$scratch/classes" scripts/native-smoke/NativeWhisper.java scripts/native-smoke/ModelComparisonSmoke.java
java -cp "$scratch/classes" org.altiro.inference.ModelComparisonSmoke \
  "$scratch/native/libaltiro-whisper.so" "$speech" "$@"
