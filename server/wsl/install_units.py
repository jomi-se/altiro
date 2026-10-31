"""Install reviewed unit files and derive the probe from the worker policy."""
import os
import shutil
import subprocess
from pathlib import Path

source = Path(__file__).resolve().parent
destination = Path("/etc/systemd/system")
names = ("altiro.service", "altiro-proxy.socket", "altiro-proxy.service", "altiro-interop-guard.service")
for name in names:
    shutil.copyfile(source / name, destination / name)
    os.chmod(destination / name, 0o644)
unit = (source / "altiro.service").read_text()
lines = []
for line in unit.splitlines():
    if line.startswith("ExecStart="):
        line = "ExecStart=/opt/altiro/.venv/bin/python /opt/altiro/wsl/probe_isolation.py"
    elif line == "Type=simple":
        line = "Type=oneshot\nRemainAfterExit=true"
    elif line == "Restart=on-failure":
        line = "Restart=no"
    elif line == "RuntimeDirectory=altiro":
        line = "RuntimeDirectory=altiro-probe"
    lines.append(line)
probe = destination / "altiro-isolation-check.service"
probe.write_text("\n".join(lines) + "\n")
os.chmod(probe, 0o644)
subprocess.run(["systemd-analyze", "verify", *[str(destination / name) for name in names], str(probe)], check=True)
subprocess.run(["systemctl", "daemon-reload"], check=True)
