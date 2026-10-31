"""Windows-side deployment through WSL stdin, without sharing Windows folders."""
import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import tarfile
import time
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
DISTRO = "Altiro-Isolated"
STATE = ROOT / ".runtime" / "wsl-install"
WSL = str(Path(os.environ.get("WINDIR", "C:/Windows")) / "System32" / "wsl.exe")
OWNER = STATE / "deployment.json"


def decode_output(data):
    if data.startswith(b"\xff\xfe") or b"\x00" in data[:100]:
        return data.decode("utf-16-le", errors="replace").lstrip("\ufeff")
    return data.decode("utf-8", errors="replace")


def run(arguments, data=None, check=True):
    result = subprocess.run([WSL, *arguments], input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = decode_output(result.stdout)
    if output.strip():
        print(output.rstrip(), flush=True)
    if check and result.returncode:
        raise RuntimeError(f"WSL operation failed ({result.returncode})")
    return result.returncode, output


def linux(script, check=True):
    return run(["--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "/bin/bash", "-seu"], script.encode(), check=check)


def require_platform():
    status = STATE / "platform-status.json"
    if status.exists():
        platform = json.loads(status.read_text(encoding="utf-8-sig"))
        if platform.get("phase") != "installed":
            raise RuntimeError("Windows WSL installation has not completed; administrator approval may be pending.")
        if platform.get("restartRequired") and not OWNER.exists():
            # This marker remains historical after a reboot. An actual VM boot
            # later decides readiness; never restart Windows from this tool.
            print("The platform installer requested a Windows restart. Checking actual WSL readiness.", flush=True)
    run(["--version"])


def configure_host():
    """Install explicit VM limits without overwriting unrelated WSL settings."""
    config_path = Path(os.environ["USERPROFILE"]) / ".wslconfig"
    expected = (ROOT / "wsl" / "default.wslconfig").read_text()
    if config_path.exists():
        if config_path.read_text(encoding="utf-8-sig").strip() != expected.strip():
            raise RuntimeError("An existing .wslconfig needs review; refusing to overwrite other WSL settings.")
    else:
        config_path.write_text(expected, encoding="utf-8")
        (STATE / "host-config.json").write_text(json.dumps({"path": str(config_path), "sha256": hashlib.sha256(expected.encode()).hexdigest()}))


def prepare_payload():
    payload = STATE / "altiro-payload.tar"
    files = ["server.py", "gpu_runtime.py", "smoke_test.py", "web/index.html", "tests/test_server.py", "samples/jfk.wav"]
    files += [str(path.relative_to(ROOT)).replace("\\", "/") for path in (ROOT / "wsl").glob("*") if path.is_file() and path.suffix in (".sh", ".py", ".service", ".socket", ".lock", ".in")]
    files += ["models/large-v3-turbo/" + name for name in ("model.bin", "config.json", "preprocessor_config.json", "tokenizer.json", "vocabulary.json", "verified.json")]
    fingerprint = []
    for name in files:
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            raise RuntimeError("Missing or unexpected payload file: " + name)
        stat = path.stat()
        fingerprint.append((name, stat.st_size, stat.st_mtime_ns))
    manifest = STATE / "payload-manifest.json"
    expected_manifest = json.dumps(fingerprint, sort_keys=True)
    if payload.exists() and manifest.exists() and manifest.read_text() == expected_manifest:
        return payload
    STATE.mkdir(parents=True, exist_ok=True)
    with tarfile.open(payload, "w") as archive:
        for name in files:
            path = ROOT / name
            info = archive.gettarinfo(str(path), arcname=name)
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mode = 0o644
            with path.open("rb") as source:
                archive.addfile(info, source)
    manifest.write_text(expected_manifest)
    print("Prepared source/model payload with an explicit file allowlist.", flush=True)
    return payload


def deploy():
    require_platform()
    configure_host()
    _, listing = run(["--list", "--quiet"])
    existing = DISTRO in [line.strip() for line in listing.splitlines()]
    if existing and not OWNER.exists():
        raise RuntimeError("A distro with this name already exists without this deployment's ownership record; refusing to modify it.")
    if not existing:
        metadata = json.loads((STATE / "ubuntu-image.json").read_text(encoding="utf-8-sig"))
        image = Path(metadata["file"])
        with image.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != metadata["sha256"]:
            raise RuntimeError("Ubuntu image checksum mismatch")
        storage = ROOT / ".runtime" / "wsl" / DISTRO
        storage.mkdir(parents=True, exist_ok=True)
        run(["--import", DISTRO, str(storage), str(image), "--version", "2"])
        OWNER.write_text(json.dumps({"distro": DISTRO, "storage": str(storage), "imported": True}, indent=2))
    # Only trusted OS commands run before disabling host integration. No package
    # downloads/installations occur until the distro has restarted with this file.
    config = (ROOT / "wsl" / "wsl.conf").read_text()
    linux("getent passwd altiro >/dev/null || useradd --system --home-dir /var/empty --shell /usr/sbin/nologin altiro\ncat > /etc/wsl.conf <<'ALTIRO_CONFIG'\n" + config + "\nALTIRO_CONFIG\nmkdir -p /opt/altiro\n")
    run(["--terminate", DISTRO])
    linux("test ! -e /mnt/c/Windows\nif test -e /proc/sys/fs/binfmt_misc/WSLInterop; then printf -- -1 > /proc/sys/fs/binfmt_misc/WSLInterop; fi\ntest ! -e /proc/sys/fs/binfmt_misc/WSLInterop\n")
    payload = prepare_payload()
    with payload.open("rb") as source:
        process = subprocess.Popen([WSL, "--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "tar", "-xf", "-", "-C", "/opt/altiro"], stdin=source)
        if process.wait():
            raise RuntimeError("Payload transfer into Linux failed")
    # This installs Python wheels inside Linux under a separate unprivileged
    # build identity; the worker later runs with stricter OS restrictions.
    bootstrap = subprocess.Popen([WSL, "--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "bash", "/opt/altiro/wsl/bootstrap.sh"])
    if bootstrap.wait():
        raise RuntimeError("Linux bootstrap failed; phone endpoint has not been changed")
    print("Deployment bootstrapped. Run verification before adding the test proxy.", flush=True)


def sync_source():
    if not OWNER.exists():
        raise RuntimeError("No owned Linux deployment exists")
    archive_bytes = io.BytesIO()
    files = [ROOT / name for name in ("server.py", "gpu_runtime.py", "smoke_test.py", "web/index.html", "tests/test_server.py")]
    files += [path for path in (ROOT / "wsl").glob("*") if path.is_file() and path.suffix in (".sh", ".py", ".service", ".socket", ".lock", ".in")]
    with tarfile.open(fileobj=archive_bytes, mode="w") as archive:
        for path in files:
            if path.is_symlink():
                raise RuntimeError("Unexpected symlink in source payload")
            info = archive.gettarinfo(str(path), arcname=str(path.relative_to(ROOT)).replace("\\", "/"))
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mode = 0o644
            with path.open("rb") as source:
                archive.addfile(info, source)
    run(["--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "tar", "-xf", "-", "-C", "/opt/altiro"], archive_bytes.getvalue())
    print("Updated allowlisted Linux source and service definitions.", flush=True)


def verify():
    require_platform()
    linux("systemctl restart altiro-isolation-check.service\n")
    _, invocation = run(["--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "systemctl", "show", "altiro-isolation-check.service", "--property=InvocationID", "--value"])
    _, probe_output = run(["--distribution", DISTRO, "--user", "root", "--cd", "/", "--exec", "journalctl", "--no-pager", "--output=cat", "_SYSTEMD_INVOCATION_ID=" + invocation.strip()])
    report = json.loads(probe_output)
    if not report.get("passed"):
        raise RuntimeError("Isolation probe did not pass")
    (STATE / "isolation-report.json").write_text(json.dumps(report, indent=2))
    linux("systemctl is-active altiro.service altiro-proxy.socket\nsystemctl show altiro.service -p User -p NoNewPrivileges -p RestrictAddressFamilies -p CapabilityBoundingSet -p ProtectSystem\n")
    import httpx
    with httpx.Client(timeout=5, trust_env=False) as client:
        for attempt in range(90):
            try:
                response = client.get("http://127.0.0.1:8875/health")
                if response.status_code == 200 and response.json().get("ready"):
                    print(json.dumps(response.json(), indent=2), flush=True)
                    break
            except httpx.HTTPError:
                pass
            time.sleep(1)
        else:
            linux("journalctl --no-pager -u altiro.service -u altiro-proxy.service -n 80", check=False)
            raise RuntimeError("Isolated GPU service did not become ready")
    result = subprocess.run([sys.executable, str(ROOT / "smoke_test.py"), "http://127.0.0.1:8875", str(STATE / "linux-benchmark.json")])
    if result.returncode:
        raise RuntimeError("Isolated end-to-end speech checks failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "deploy", "sync-source", "verify"))
    action = parser.parse_args().action
    {"prepare": prepare_payload, "deploy": deploy, "sync-source": sync_source, "verify": verify}[action]()
