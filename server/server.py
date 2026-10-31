"""Private dictation API. No remote services are used during recognition."""
import asyncio
import contextlib
import hashlib
import hmac
import io
import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["DO_NOT_TRACK"] = "1"

from gpu_runtime import configure_gpu_dlls

configure_gpu_dlls()

import av
import ctranslate2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
from faster_whisper import WhisperModel

ROOT = Path(__file__).resolve().parent
MODEL_PATH = ROOT / "models" / "large-v3-turbo"
MODEL_ID = "large-v3-turbo"
MAX_BYTES = 16 * 1024 * 1024
MAX_SECONDS = 300
logger = logging.getLogger("altiro")


def decode_bounded(data: bytes) -> np.ndarray:
    """Decode common phone/browser formats, bounded to five minutes at 16 kHz."""
    if not data:
        raise ValueError("Audio file is empty")
    buffers = []
    sample_count = 0
    try:
        with av.open(io.BytesIO(data), mode="r") as container:
            if not container.streams.audio:
                raise ValueError("File contains no audio")
            stream = container.streams.audio[0]
            resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
            for frame in container.decode(stream):
                for result in resampler.resample(frame):
                    samples = result.to_ndarray().flatten()
                    sample_count += len(samples)
                    if sample_count > MAX_SECONDS * 16000:
                        raise ValueError("Audio exceeds the five-minute limit")
                    buffers.append(samples)
            for result in resampler.resample(None):
                samples = result.to_ndarray().flatten()
                sample_count += len(samples)
                if sample_count > MAX_SECONDS * 16000:
                    raise ValueError("Audio exceeds the five-minute limit")
                buffers.append(samples)
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError("Cannot decode this audio file") from exc
    if not sample_count:
        raise ValueError("Audio file contains no samples")
    return np.concatenate(buffers).astype(np.float32) / 32768.0


class Engine:
    def __init__(self):
        self.status = "loading"
        self.error = None
        self.model = None
        self.busy = threading.Lock()
        self.compute_type = "int8_float32"
        self.load_seconds = None

    def load(self):
        started = time.perf_counter()
        try:
            metadata = json.loads((MODEL_PATH / "verified.json").read_text(encoding="utf-8"))
            with (MODEL_PATH / "model.bin").open("rb") as weights:
                digest = hashlib.file_digest(weights, "sha256").hexdigest()
            if digest != metadata["weight_sha256"]:
                raise RuntimeError("Model checksum mismatch")
            supported = ctranslate2.get_supported_compute_types("cuda", 0)
            if self.compute_type not in supported:
                raise RuntimeError("GPU does not support the configured compute type")
            self.model = WhisperModel(
                str(MODEL_PATH), device="cuda", device_index=0,
                compute_type=self.compute_type, cpu_threads=4, num_workers=1,
                local_files_only=True,
            )
            # Exercise CUDA/cuDNN kernels, not just successful DLL/model loading.
            segments, _ = self.model.transcribe(
                np.zeros(16000, dtype=np.float32), language="en", beam_size=1,
                condition_on_previous_text=False, vad_filter=False,
            )
            list(segments)
            # Also initialize the VAD path before declaring the service ready.
            vad_segments, _ = self.model.transcribe(
                np.zeros(16000, dtype=np.float32), language="en", vad_filter=True,
            )
            list(vad_segments)
            self.load_seconds = round(time.perf_counter() - started, 2)
            self.status = "ready"
            logger.info("GPU model ready in %.2f seconds", self.load_seconds)
        except Exception as exc:
            self.status = "error"
            self.error = "gpu_initialization_failed"
            logger.error("GPU initialization failed: %s", type(exc).__name__)
            # Local startup diagnostics only; never includes user audio/transcript.
            logger.error("Startup detail: %s", str(exc))

    def transcribe(self, data, language, prompt):
        started = time.perf_counter()
        audio = decode_bounded(data)
        duration = len(audio) / 16000
        segments, info = self.model.transcribe(
            audio, task="transcribe", language=language,
            beam_size=5, best_of=5, temperature=0,
            condition_on_previous_text=False, vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 400, "speech_pad_ms": 200},
            initial_prompt=prompt or None,
        )
        text = "".join(segment.text for segment in segments).strip()
        elapsed = time.perf_counter() - started
        return {
            "text": text,
            "raw_text": text,
            "language": info.language,
            "language_probability": info.language_probability,
            "duration": round(duration, 3),
            "processing_seconds": round(elapsed, 3),
            "model": MODEL_ID,
            "device": "cuda",
            "cleanup_applied": False,
        }


engine = Engine()


@asynccontextmanager
async def lifespan(app):
    task = asyncio.create_task(asyncio.to_thread(engine.load))
    yield
    await task


app = FastAPI(title="Altiro private speech server", version="0.1.0", lifespan=lifespan)


class BodyLimit:
    """Bound multipart uploads even when Content-Length is absent."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        limit = MAX_BYTES + 65536
        headers = dict(scope.get("headers", []))
        try:
            length = int(headers.get(b"content-length", b"0"))
        except ValueError:
            return await JSONResponse({"detail": "Invalid Content-Length"}, status_code=400)(scope, receive, send)
        if length > limit:
            return await JSONResponse({"detail": "Upload exceeds 16 MiB"}, status_code=413)(scope, receive, send)
        consumed = 0

        async def limited_receive():
            nonlocal consumed
            message = await receive()
            consumed += len(message.get("body", b""))
            if consumed > limit:
                raise HTTPException(413, "Upload exceeds 16 MiB")
            return message

        await self.app(scope, limited_receive, send)


app.add_middleware(BodyLimit)


@app.middleware("http")
async def private_responses(request: Request, call_next):
    api_key = os.environ.get('ALTIRO_API_KEY', '')
    if api_key and request.url.path.startswith('/v1/'):
        if not hmac.compare_digest(request.headers.get('Authorization', '').encode('utf-8'), ('Bearer ' + api_key).encode('utf-8')):
            return JSONResponse({'detail': 'Bearer authentication required'}, status_code=401, headers={'Cache-Control': 'no-store'})
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    return response


@app.get("/health")
async def health():
    return JSONResponse({
        "status": engine.status, "ready": engine.status == "ready",
        "busy": engine.busy.locked(), "model": MODEL_ID, "device": "cuda",
        "compute_type": engine.compute_type, "load_seconds": engine.load_seconds,
        "max_audio_seconds": MAX_SECONDS, "max_upload_bytes": MAX_BYTES,
        "languages": ["en", "fr", "es", "auto"],
        "cleanup_available": False, "error": engine.error,
    }, status_code=200 if engine.status == "ready" else 503)


@app.get("/v1/models")
async def models():
    return {"object": "list", "data": [{"id": MODEL_ID, "object": "model", "owned_by": "local"}]}


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: Annotated[UploadFile, File()],
    model: Annotated[str, Form()] = MODEL_ID,
    language: Annotated[str | None, Form()] = None,
    prompt: Annotated[str, Form()] = "",
    response_format: Annotated[str, Form()] = "json",
    temperature: Annotated[float, Form()] = 0,
):
    try:
        if model not in (MODEL_ID, "whisper-1"):
            raise HTTPException(400, "Unknown model; use large-v3-turbo")
        if language in ("", "auto"):
            language = None
        if language is not None and language not in ("en", "es", "fr"):
            raise HTTPException(400, "Language must be en, es, fr or auto")
        if response_format not in ("json", "verbose_json", "text"):
            raise HTTPException(400, "Response format must be json, verbose_json or text")
        if temperature != 0:
            raise HTTPException(400, "Only temperature=0 is supported")
        if len(prompt.encode("utf-8")) > 4096:
            raise HTTPException(400, "Vocabulary prompt exceeds 4 KiB")
        if engine.status != "ready":
            raise HTTPException(503, "GPU model is not ready", headers={"Retry-After": "5"})
        if not engine.busy.acquire(blocking=False):
            raise HTTPException(429, "Transcription is busy", headers={"Retry-After": "2"})
        try:
            data = await file.read(MAX_BYTES + 1)
            if len(data) > MAX_BYTES:
                raise HTTPException(413, "Audio exceeds 16 MiB")
            # Keep the slot until the worker actually finishes, including disconnects.
            worker = asyncio.create_task(asyncio.to_thread(engine.transcribe, data, language, prompt))
            try:
                result = await asyncio.shield(worker)
            except asyncio.CancelledError:
                with contextlib.suppress(Exception):
                    await worker
                raise
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        except HTTPException:
            raise
        except Exception as exc:
            logger.error("Recognition failed: %s", type(exc).__name__)
            raise HTTPException(503, "GPU recognition failed") from exc
        finally:
            engine.busy.release()
        if response_format == "text":
            return PlainTextResponse(result["text"])
        return result
    finally:
        await file.close()


@app.get("/", response_class=HTMLResponse)
async def home():
    return (ROOT / "web" / "index.html").read_text(encoding="utf-8")


if __name__ == "__main__":
    import uvicorn

    # Tailscale Serve proxies this loopback listener over private HTTPS.
    uvicorn.run(app, host="127.0.0.1", port=8765, access_log=False, log_level="info")
