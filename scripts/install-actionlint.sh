#!/bin/sh
set -eu
version=1.7.12
case "$(uname -s):$(uname -m)" in
  Linux:x86_64 | Linux:amd64)
    platform=linux_amd64
    checksum=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
    ;;
  Linux:aarch64 | Linux:arm64)
    platform=linux_arm64
    checksum=325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6
    ;;
  Darwin:x86_64 | Darwin:amd64)
    platform=darwin_amd64
    checksum=5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644
    ;;
  Darwin:arm64 | Darwin:aarch64)
    platform=darwin_arm64
    checksum=aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f
    ;;
  *)
    echo "Unsupported actionlint installer platform: $(uname -s) $(uname -m)" >&2
    exit 1
    ;;
esac
git_dir=$(git rev-parse --absolute-git-dir)
install_dir=${ACTIONLINT_INSTALL_DIR:-$git_dir/altiro-tools}
for command_name in curl tar; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "$command_name is required to install actionlint." >&2
    exit 1
  fi
done
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/altiro-actionlint.XXXXXX")
trap 'rm -rf -- "$temp_dir"' EXIT HUP INT TERM
archive=$temp_dir/actionlint.tar.gz
url="https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_${platform}.tar.gz"
curl --fail --location --silent --show-error --output "$archive" "$url"
if command -v sha256sum >/dev/null 2>&1; then
  printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
elif command -v shasum >/dev/null 2>&1; then
  actual_checksum=$(shasum -a 256 "$archive" | awk '{print $1}')
  if test "$actual_checksum" != "$checksum"; then
    echo 'actionlint archive checksum verification failed.' >&2
    exit 1
  fi
else
  echo 'sha256sum or shasum is required to verify actionlint.' >&2
  exit 1
fi
tar -xzf "$archive" -C "$temp_dir" actionlint
mkdir -p "$install_dir"
cp "$temp_dir/actionlint" "$install_dir/actionlint"
chmod 0755 "$install_dir/actionlint"
echo "Installed actionlint $version at $install_dir/actionlint"
