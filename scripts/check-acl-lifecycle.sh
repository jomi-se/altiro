#!/bin/sh
# Host fake-driver test using the same pinned/generated headers as the native build.
set -eu
if [ "$#" -ne 1 ]; then
    echo 'Usage: check-acl-lifecycle.sh NATIVE_BUILD' >&2
    exit 2
fi
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
native_build=$(CDPATH= cd -- "$1" && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/altiro-acl-lifecycle-XXXXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
c++ -std=c++17 -O1 -ffunction-sections -fdata-sections -DALTIRO_VULKAN=1 \
    -I"$native_build" \
    -I"$native_build/_deps/opencl_headers-src" \
    -I"$native_build/_deps/vulkan_headers-src/include" \
    -I"$native_build/_deps/transcribepsy-src/ggml/include" \
    -I"$native_build/_deps/transcribepsy-src/ggml/src" \
    "$repo_root/inference-transcribepsy/src/test-native/AclLifecyclePolicies.cpp" \
    -Wl,--gc-sections -ldl -o "$scratch/acl-lifecycle"
"$scratch/acl-lifecycle"
