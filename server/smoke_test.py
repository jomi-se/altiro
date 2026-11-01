"""Exercise real speech, silence, and invalid data through the running API."""
import io
import json
import sys
import time
import wave
from pathlib import Path
import hashlib
from urllib.request import urlopen

import httpx

ROOT = Path(__file__).resolve().parent
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8875"
REPORT = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "verification.local.json"


def prepare_sample():
    sample = ROOT / 'samples' / 'jfk.wav'
    if not sample.exists():
        sample.parent.mkdir(parents=True, exist_ok=True)
        with urlopen('https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.4/samples/jfk.wav', timeout=30) as source:
            content = source.read(1024 * 1024)
        if hashlib.sha256(content).hexdigest() != '59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e':
            raise RuntimeError('Public test-sample checksum mismatch')
        sample.write_bytes(content)


def main():
    prepare_sample()
    results = {"base_url": BASE, "tests": []}
    with httpx.Client(timeout=180, trust_env=False) as client:
        for attempt in range(60):
            health = client.get(BASE + "/health")
            if health.status_code == 200:
                break
            time.sleep(1)
        health.raise_for_status()
        assert health.json()["ready"] and health.json()["device"] == "cuda"
        results["health"] = health.json()
        for pass_number in range(2):
            started = time.perf_counter()
            with (ROOT / "samples" / "jfk.wav").open("rb") as audio:
                response = client.post(BASE + "/v1/audio/transcriptions", files={"file": ("jfk.wav", audio, "audio/wav")}, data={"language": "en", "model": "large-v3-turbo"})
            response.raise_for_status()
            result = response.json()
            assert "country" in result["text"].lower(), result
            assert result["cleanup_applied"] is False
            result["round_trip_seconds"] = round(time.perf_counter() - started, 3)
            result["test"] = "public_jfk_speech_" + str(pass_number + 1)
            results["tests"].append(result)
            print(json.dumps(result), flush=True)
        silence = io.BytesIO()
        with wave.open(silence, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b"\x00\x00" * 32000)
        response = client.post(BASE + "/v1/audio/transcriptions", files={"file": ("silence.wav", silence.getvalue(), "audio/wav")}, data={"language": "en"})
        response.raise_for_status()
        assert response.json()["text"] == "", response.text
        results["tests"].append({"test": "digital_silence", "passed": True})
        response = client.post(BASE + "/v1/audio/transcriptions", files={"file": ("invalid.wav", b"invalid")})
        assert response.status_code == 400, response.text
        results["tests"].append({"test": "invalid_audio", "passed": True})
        assert client.get(BASE + "/health").json()["busy"] is False
    REPORT.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("End-to-end GPU/API checks passed.", flush=True)


if __name__ == "__main__":
    main()
