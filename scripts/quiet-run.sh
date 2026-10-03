#!/bin/sh
# Portable adapter: shared quiet-run owns detached execution and log cleanup.
set -eu

if command -v quiet-run >/dev/null 2>&1; then
  exec quiet-run "$@"
fi

if [ "$#" -lt 2 ]; then
  echo 'Usage: scripts/quiet-run.sh "label" command [args...]' >&2
  exit 2
fi

case "$1" in
  --*)
    echo 'Detached/status/cleanup options require shared quiet-run on PATH.' >&2
    exit 2
    ;;
esac

shift
exec "$@"
