# Optional self-hosted Altiro speech server

This component serves multilingual Whisper large-v3-turbo using the operator's
NVIDIA GPU. The Android app remains useful offline and builds independently of
this directory. Android integrates this endpoint through Settings → Your speech server, with
remote first and selected-local-model fallback. See the
[phone procedure](../docs/testing/remote-first.md); end-to-end phone acceptance
remains separate from the reference server smoke checks. The browser client
can also record/upload audio and copy transcripts.

The reference deployment uses Windows 11, WSL2/Ubuntu 24.04 and a restricted Linux
worker. Its generic bootstrap scripts are versioned; machine data and downloaded
software stay in ignored directories. See [the decision](../docs/decisions/0014-optional-self-hosted-server.md)
and [isolation policy](wsl/README.md).

## Layout

- `server.py`, `gpu_runtime.py`: bounded transcription API and GPU runtime setup.
- `web/`: same-origin browser test client; microphone use requires HTTPS.
- `tests/`, `smoke_test.py`: API/decoder tests and real-GPU public-audio checks.
- `requirements.control.*`: small Windows orchestration environment.
- `requirements.*`: optional native Windows diagnostic runtime.
- `wsl/requirements.linux.*`: separately hash-locked Linux inference runtime.
- `download_model.py`: fixed model revision and SHA-256 verification.
- `wsl/`: VM deployment, restricted services and effective isolation probes.
- PowerShell scripts: preparation, start/stop, private proxy and sign-in startup.

`.runtime/`, `.venv/`, `models/`, `samples/`, logs and local JSON reports are not
source. They must never be committed. Keep the checkout at a stable location;
registered WSL storage and sign-in shortcuts depend on its installation path.

## Prepare and deploy on Windows

Prerequisites: Windows 11 with firmware virtualization enabled, a compatible
NVIDIA driver/card, Tailscale installed and joined to the operator's tailnet,
and enough disk/RAM for the VM and model. The tested Pascal-class profile uses
CUDA 12.4 libraries and `int8_float32`; do not assume CUDA 13/newer hardware-only
packages work with an older card. No driver or Linux display driver is installed
by these scripts. WSL's GPU path uses the Windows driver.

From this directory in PowerShell:

```powershell
.\Prepare-Isolated.ps1
```

This prepares project-local Python control tools and verified downloads. If WSL
is absent, run `Initialize-WSL-Platform.ps1` through Windows' normal administrator
approval flow. It installs the verified Microsoft MSI and enables Virtual Machine
Platform without restarting Windows. Restart/sign back in if Windows requires it.

Then:

```powershell
.\Prepare-Isolated.ps1 -Deploy
.\Connect-Tailnet.ps1
.\Enable-LoginStartup.ps1
```

Use the private HTTPS URL printed by Tailscale Serve. A reserved example is
`https://speech.example.net/`; no personal endpoint is part of this repository.
Existing tailnet access rules govern access. The default profile is private,
with no Funnel, router forwarding or public internet signup.

The main endpoint accepts `POST /v1/audio/transcriptions`; configure the full
URL `https://speech.example.net/v1/audio/transcriptions` in the Android
adapter (the HTTPS origin alone is also accepted). Its minimal compatible response is `{"text":"..."}`. See
[the API contract](API.md).

## Operation

```powershell
.\Start-Server.ps1
.\Stop-Server.ps1
.\Enable-LoginStartup.ps1
.\Disable-LoginStartup.ps1
```

These default to the isolated service. Startup occurs at the current user's
Windows sign-in, not before sign-in. A hidden WSL keepalive keeps the dedicated
environment running. The model stays loaded; health returns 503 until startup
verification/warm-up completes. The laptop must stay powered on, awake and signed
into its existing Tailscale identity. Sleep/lid/key-expiry settings are unchanged.
Linux systemd restarts a failed worker, stopping repeated crash loops.

Inspect the worker with `wsl -d Altiro-Isolated -u root --exec journalctl -u altiro`.
Request access/body logging is disabled. Uploads may briefly spool into private
temporary storage and are closed after processing. Results are transient; there
is no transcript history, database or LLM cleanup model.

The native Windows diagnostic profile is available with `setup.ps1` without
`-ControlOnly`, `Start-NativeServer.ps1` and `Stop-NativeServer.ps1`. It is not
OS-sandboxed and is never selected by the default sign-in launcher.

## Verification

```powershell
.\.venv\Scripts\python.exe -u wsl\manage.py verify
.\.venv\Scripts\python.exe smoke_test.py https://speech.example.net
```

The smoke command downloads a checksum-pinned public test clip if absent; its
download is explicit test/setup activity, not inference networking. The worker
itself has no IP sockets. Run API tests with `python -m pytest` in a compatible
inference environment; the minimal Windows control environment deliberately
does not install the GPU inference stack.

Effective isolation checks and real GPU recognition are separate from package
installation, syntax checks and dependency scanning. The reference passed 18
containment checks and real speech/silence/error/restart smoke checks. Additional
bearer-auth contract coverage is included in the source tests. Warm processing
of an 11-second English sample was roughly 0.8–0.9 seconds on the reference laptop.
This does not establish arbitrary-hardware compatibility, multilingual accuracy,
driver/hypervisor safety or parity with hosted dictation.

## Dependencies and licensing

New source follows the repository's Apache-2.0 license. Third-party packages,
NVIDIA libraries and model weights retain their own licenses; no downloaded
binaries or weights are distributed in Git. See [dependencies](DEPENDENCIES.md).
