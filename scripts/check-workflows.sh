#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
git_dir=$(git rev-parse --absolute-git-dir)
binary=${ACTIONLINT_BIN:-$git_dir/altiro-tools/actionlint}
if ! test -x "$binary"; then
  echo 'Install actionlint with ./scripts/install-actionlint.sh, or set ACTIONLINT_BIN.' >&2
  exit 1
fi
# Check Actions grammar, contexts and types. Shell/Python checks are independent.
exec "$binary" -color -shellcheck= -pyflakes=
