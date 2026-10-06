#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
if [ "$#" -lt 2 ]; then
  echo 'Usage: check-whisper-native.sh VERIFIED_BASE_MODEL CANONICAL_JFK_WAV [cmake arguments...]' >&2
  exit 2
fi
model=$1
speech=$2
shift 2
python3 scripts/check-model-manifest.py "$model"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/altiro-native-smoke-XXXXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
cmake -S inference-whisper/src/main/cpp -B "$scratch/native" -DCMAKE_BUILD_TYPE=Release "$@"
cmake --build "$scratch/native" --parallel 2
javac -d "$scratch/classes" scripts/native-smoke/NativeWhisper.java
java -cp "$scratch/classes" org.altiro.inference.NativeWhisper \
  "$scratch/native/libaltiro-whisper.so" "$model" "$speech" "$scratch"
if [ "${ALTIRO_TEST_GPU_UNAVAILABLE:-0}" = 1 ]; then
  java -cp "$scratch/classes" org.altiro.inference.NativeWhisper \
    "$scratch/native/libaltiro-whisper.so" "$model" "$speech" "$scratch" --gpu-unavailable
fi
