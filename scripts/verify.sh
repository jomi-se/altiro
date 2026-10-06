#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
python3 scripts/check-repository.py
python3 scripts/check-model-manifest.py
case "${1:-}" in
  --docs-only) exit 0 ;;
  --core-only)
    shift
    ./gradlew --no-daemon -PcoreOnly=true spotlessApply "$@"
    exec ./gradlew --no-daemon -PcoreOnly=true :core:test :network:test "$@"
    ;;
  --) shift ;;
esac
# Format before compilation so parallel Gradle tasks never race source edits.
./gradlew --no-daemon spotlessApply "$@"
exec ./gradlew --no-daemon :core:test :network:test checkNativePages \
  :app:assembleDebug :editor-fixture:assembleDebug :inference-whisper:assembleDebug \
  :app:lintDebug :editor-fixture:lintDebug \
  :app:assembleDebugAndroidTest :editor-fixture:assembleDebugAndroidTest "$@"
