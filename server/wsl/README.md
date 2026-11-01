# Restricted WSL2 deployment

The launcher manages one dedicated `Altiro-Isolated` Ubuntu 24.04 distribution.
Source and verified model files cross through an explicit tar allowlist over
WSL stdin. Windows drives are not mounted and executable interop is disabled.
Unrelated WSL distributions are left alone.

Linux contains `/opt/altiro` and three separate identities:

- `altiro-build` installs hash-locked binary packages during maintenance.
- `altiro` loads the model and GPU runtime, serving a private Unix socket.
- `altiro-proxy` forwards Linux loopback port 8875 to that socket.

The worker has no IP sockets, privileges, writable application/model files or
Windows paths. systemd applies read-only system storage, private temporary
storage, device/resource limits and syscall restrictions. A boot guard removes
WSL's PE executable handler before worker startup. A private read-only view hides
systemd management sockets. The unit files contain the complete policy.

Windows Tailscale Serve terminates private HTTPS and forwards to the loopback
proxy. The worker cannot reach the tailnet or internet. Proxy/build processes
have different restrictions because they need networking for their own tasks.
The Windows GPU driver, WSL kernel/hypervisor and host administrator remain
trusted. Host-side control tools are outside the restricted inference worker.

`default.wslconfig` sets VM limits and disables WSLg. Those limits apply to the
user's WSL2 VM generally. Deployment refuses to replace a differing existing
configuration; review it explicitly if other WSL2 workloads exist.

## Maintenance

From `server/` on Windows:

```powershell
.\.venv\Scripts\python.exe -u wsl\manage.py sync-source
wsl -d Altiro-Isolated -u root --cd /opt/altiro --exec python3 wsl/install_units.py
wsl -d Altiro-Isolated -u root --exec systemctl restart altiro.service
.\.venv\Scripts\python.exe -u wsl\manage.py verify
```

The probe runs with the worker's effective systemd policy. It checks denied
IP sockets, host paths/mounting, privileges and writes, plus model/GPU access.
Reports stay ignored. Dependency changes require deployment bootstrap and real
GPU/isolation checks; a syntax check or audit is separate evidence.

Use `Stop-Server.ps1` for routine stopping. `wsl --terminate Altiro-Isolated`
also stops this distribution. Do not unregister it for a restart: that deletes
its disk. See [setup](../README.md), [API](../API.md) and
[dependency provenance](../DEPENDENCIES.md).
