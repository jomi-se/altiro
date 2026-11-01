"""Run with the SAME systemd restrictions as the worker, not just its UID."""
import json
import os
import socket
import subprocess
from pathlib import Path

checks = {}
checks["non_root"] = os.geteuid() != 0
status = Path("/proc/self/status").read_text()
checks["no_new_privileges"] = "NoNewPrivs:\t1" in status
caps = next(line.split()[1] for line in status.splitlines() if line.startswith("CapEff:"))
checks["no_effective_capabilities"] = int(caps, 16) == 0
checks["windows_interop_unregistered"] = not Path("/proc/sys/fs/binfmt_misc/WSLInterop").exists()
for family, name in ((socket.AF_INET, "ipv4"), (socket.AF_INET6, "ipv6")):
    try:
        connection = socket.socket(family, socket.SOCK_STREAM)
    except OSError:
        checks[name + "_socket_denied"] = True
    else:
        connection.close()
        checks[name + "_socket_denied"] = False
for directory in ("/mnt", "/home", "/root", "/run/WSL"):
    try:
        list(Path(directory).iterdir())
    except (PermissionError, FileNotFoundError):
        checks[directory + "_inaccessible"] = True
    else:
        checks[directory + "_inaccessible"] = False
for directory in ("/opt/altiro", "/opt/altiro/models/large-v3-turbo", "/etc", "/run/systemd"):
    canary = Path(directory) / ".altiro-write-probe"
    try:
        canary.write_text("probe")
    except OSError:
        checks[directory + "_read_only"] = True
    else:
        canary.unlink()
        checks[directory + "_read_only"] = False
checks["model_readable"] = os.access("/opt/altiro/models/large-v3-turbo/model.bin", os.R_OK)
checks["gpu_device_available"] = os.access("/dev/dxg", os.R_OK | os.W_OK)
checks["systemd_management_sockets_hidden"] = not any(
    Path(path).exists() for path in (
        "/run/systemd/private", "/run/systemd/resolve", "/run/systemd/userdb", "/run/systemd/notify",
    )
)
try:
    subprocess.run(["mount", "-t", "drvfs", "C:", "/tmp"], check=True, capture_output=True)
except (subprocess.CalledProcessError, OSError):
    checks["host_mount_denied"] = True
else:
    checks["host_mount_denied"] = False
print(json.dumps({"passed": all(checks.values()), "checks": checks}, indent=2), flush=True)
raise SystemExit(0 if all(checks.values()) else 1)
