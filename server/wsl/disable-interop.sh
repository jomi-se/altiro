#!/bin/bash
set -euo pipefail
# WSL 3.0.3 can leave the PE binfmt handler registered despite interop=false.
if [[ -e /proc/sys/fs/binfmt_misc/WSLInterop ]]; then
    printf -- -1 > /proc/sys/fs/binfmt_misc/WSLInterop
fi
test ! -e /proc/sys/fs/binfmt_misc/WSLInterop
if findmnt -rn -t drvfs,9p | grep -qE '/mnt/[a-z]([ /]|$)'; then
    echo 'Unexpected Windows drive mount detected.' >&2
    exit 1
fi
