"""Keep the private GPU API running; stop retrying repeated startup failures."""
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    failures = []
    while True:
        print("Starting Altiro GPU API", flush=True)
        child = subprocess.Popen([sys.executable, "-u", str(ROOT / "server.py")], cwd=ROOT)
        exit_code = child.wait()
        if exit_code == 0:
            return
        now = time.monotonic()
        failures = [failure for failure in failures if now - failure < 300]
        failures.append(now)
        print(f"GPU API exited ({exit_code}); recent failures: {len(failures)}", flush=True)
        if len(failures) >= 3:
            print("Stopping after three failures in five minutes. Check logs and restart manually.", flush=True)
            return
        time.sleep(5)


if __name__ == "__main__":
    main()
