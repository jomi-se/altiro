#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
python3 scripts/check-repository.py
case "${1:-}" in
  --docs-only) exit 0 ;;
  --core-only)
    shift
    exec ./gradlew --no-daemon -PcoreOnly=true spotlessCheck :core:test :network:test "$@"
    ;;
  --) shift ;;
esac
exec ./gradlew --no-daemon spotlessCheck :core:test :network:test checkNativePages \
  :app:assembleDebug :editor-fixture:assembleDebug :inference-whisper:assembleDebug \
  :app:lintDebug :editor-fixture:lintDebug \
  :app:assembleDebugAndroidTest :editor-fixture:assembleDebugAndroidTest "$@"
