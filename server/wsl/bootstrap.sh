#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
cd /opt/altiro

# Abort before installing dependencies if the intended VM restrictions are absent.
if findmnt -rn -t drvfs,9p | grep -qE '/mnt/[a-z]([ /]|$)'; then
    echo 'Windows drive mount detected; refusing dependency installation.' >&2
    exit 1
fi
if command -v powershell.exe >/dev/null 2>&1 || command -v cmd.exe >/dev/null 2>&1; then
    echo 'Windows interop PATH is present; refusing dependency installation.' >&2
    exit 1
fi
if [[ -e /proc/sys/fs/binfmt_misc/WSLInterop ]]; then
    echo 'Windows executable interop remains registered; refusing dependency installation.' >&2
    exit 1
fi

apt-get update
apt-get install --no-install-recommends -y ca-certificates python3.12-venv python3-pip
getent passwd altiro >/dev/null || useradd --system --home-dir /var/empty --shell /usr/sbin/nologin altiro
getent passwd altiro-build >/dev/null || useradd --system --home-dir /var/empty --shell /usr/sbin/nologin altiro-build
getent passwd altiro-proxy >/dev/null || useradd --system --gid altiro --home-dir /var/empty --shell /usr/sbin/nologin altiro-proxy
install -d -o root -g root -m 0755 /var/empty
python3.12 -m venv .venv
chown -R altiro-build:altiro-build .venv
# Dependency acquisition gets network access during maintenance, but no host
# integration, GPU device access, personal directories or write access to code.
systemd-run --unit=altiro-build --wait --pipe --collect \
    --property=User=altiro-build \
    --property=WorkingDirectory=/opt/altiro \
    --property=NoNewPrivileges=true \
    --property=CapabilityBoundingSet= \
    --property=ProtectSystem=strict \
    --property=ReadWritePaths=/opt/altiro/.venv \
    --property=ProtectHome=true \
    --property=PrivateTmp=true \
    --property=PrivateDevices=true \
    --property=ProtectKernelTunables=true \
    --property=ProtectKernelModules=true \
    --property=ProtectControlGroups=true \
    --property=RestrictNamespaces=true \
    --property=RestrictSUIDSGID=true \
    --property='InaccessiblePaths=-/mnt -/media -/run/WSL -/run/dbus -/run/user' \
    --property=TemporaryFileSystem=/run/systemd:ro \
    --setenv=HOME=/var/empty --setenv=PIP_NO_CACHE_DIR=1 \
    /opt/altiro/.venv/bin/python -m pip install --only-binary=:all: --require-hashes -r wsl/requirements.linux.lock
chown -R root:root /opt/altiro
chmod -R go-w /opt/altiro
python3 wsl/install_units.py
systemctl reset-failed altiro.service altiro-isolation-check.service || true
systemctl enable --now altiro-interop-guard.service altiro.service altiro-proxy.socket
echo 'Isolated service started; verification is required before phone exposure.'
