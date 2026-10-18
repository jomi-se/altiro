#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [[ $# != 5 ]]; then
  echo 'Usage: check-transcribepsy-native.sh HOST_BUILD Q8_GGUF FP16_GGUF WAV REFERENCE_PHRASE' >&2
  exit 2
fi
build=$(realpath "$1")
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" "$root/inference-transcribepsy/src/test-native/NativeTranscribePsy.java"
java -Djava.library.path="$build" -cp "$classes" org.altiro.transcribepsy.NativeTranscribePsy "$2" "$3" "$4" "$5"
