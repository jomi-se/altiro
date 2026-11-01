import io
import threading
import wave

import numpy as np
from fastapi.testclient import TestClient

import server


def wav(seconds=0.05):
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(b"\x00\x00" * int(16000 * seconds))
    return output.getvalue()


def test_bounded_decoder():
    audio = server.decode_bounded(wav())
    assert len(audio) == 800
    assert audio.dtype == np.float32
    try:
        server.decode_bounded(b"not audio")
    except ValueError:
        pass
    else:
        raise AssertionError("Invalid audio was accepted")


def test_long_audio_rejected(monkeypatch):
    monkeypatch.setattr(server, "MAX_SECONDS", 0.01)
    try:
        server.decode_bounded(wav())
    except ValueError as error:
        assert "limit" in str(error)
    else:
        raise AssertionError("Duration limit was not enforced")


def test_api_readiness_and_busy(monkeypatch):
    monkeypatch.setattr(server.engine, "status", "loading")
    client = TestClient(server.app)
    assert client.get("/health").status_code == 503
    assert client.post("/v1/audio/transcriptions", files={"file": ("a.wav", wav())}).status_code == 503
    monkeypatch.setattr(server.engine, "status", "ready")
    server.engine.busy.acquire()
    try:
        assert client.post("/v1/audio/transcriptions", files={"file": ("a.wav", wav())}).status_code == 429
    finally:
        server.engine.busy.release()


def test_transcription_contract_and_no_cleanup(monkeypatch):
    monkeypatch.setattr(server.engine, "status", "ready")
    calls = []
    def fake_transcribe(data, language, prompt):
        calls.append((language, prompt))
        server.decode_bounded(data)
        return {"text": "Bonjour.", "raw_text": "Bonjour.", "cleanup_applied": False}
    monkeypatch.setattr(server.engine, "transcribe", fake_transcribe)
    client = TestClient(server.app)
    response = client.post("/v1/audio/transcriptions", files={"file": ("a.wav", wav())}, data={"language": "fr", "prompt": "Altiro"})
    assert response.status_code == 200
    assert response.json()["text"] == "Bonjour."
    assert response.json()["cleanup_applied"] is False
    assert calls == [("fr", "Altiro")]
    assert response.headers["cache-control"] == "no-store"
    assert not server.engine.busy.locked()
    assert client.post("/v1/audio/transcriptions", files={"file": ("a.wav", wav())}, data={"model": "wrong"}).status_code == 400
    response = client.post("/v1/audio/transcriptions", files={"file": ("a.wav", wav())}, data={"response_format": "text"})
    assert response.text == "Bonjour."


def test_bad_audio_releases_slot(monkeypatch):
    monkeypatch.setattr(server.engine, "status", "ready")
    def bad_audio(*args):
        raise ValueError("Cannot decode this audio file")
    monkeypatch.setattr(server.engine, "transcribe", bad_audio)
    client = TestClient(server.app)
    response = client.post("/v1/audio/transcriptions", files={"file": ("a.wav", b"bad")})
    assert response.status_code == 400
    assert not server.engine.busy.locked()


def test_upload_limit(monkeypatch):
    monkeypatch.setattr(server, "MAX_BYTES", 1024)
    client = TestClient(server.app)
    assert client.post("/v1/audio/transcriptions", content=b"x" * 70000).status_code == 413


def test_optional_bearer_authentication(monkeypatch):
    monkeypatch.setenv('ALTIRO_API_KEY', 'test-only-placeholder')
    client = TestClient(server.app)
    assert client.get('/v1/models').status_code == 401
    assert client.get('/v1/models', headers={'Authorization': 'Bearer wrong'}).status_code == 401
    assert client.get('/v1/models', headers={b'Authorization': b'Bearer \xff'}).status_code == 401
    assert client.get('/v1/models', headers={'Authorization': 'Bearer test-only-placeholder'}).status_code == 200
    assert client.post('/v1/audio/transcriptions', content=b'invalid').status_code == 401
