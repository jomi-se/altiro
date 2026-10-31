# Server dependency boundary

The Windows control, native Windows diagnostic and Linux inference environments
have separate exact-version/hash locks. They are outside Gradle and the APK.
No third-party source, downloaded executables, model weights or vendor libraries
are committed here. Package/model acquisition happens during explicit setup;
the restricted inference worker loads local verified files only.

Key upstream components:

| Component | Purpose / upstream license boundary |
| --- | --- |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | Whisper pipeline; MIT |
| [CTranslate2](https://github.com/OpenNMT/CTranslate2) | Transformer inference; MIT |
| [FastAPI](https://github.com/fastapi/fastapi), [Starlette](https://github.com/Kludex/starlette), [Uvicorn](https://github.com/encode/uvicorn) | HTTP service; MIT / BSD licenses remain upstream |
| [PyAV](https://github.com/PyAV-Org/PyAV) | Audio decoding; PyAV and bundled FFmpeg have separate licensing |
| [NVIDIA CUDA/cuDNN](https://developer.nvidia.com/cudnn) | Vendor runtime binaries under NVIDIA's terms, not Apache-2.0 |
| [uv](https://github.com/astral-sh/uv) | Setup tooling; upstream licenses and standalone-Python notices apply |
| [Whisper large-v3-turbo](https://huggingface.co/openai/whisper-large-v3-turbo) | Original OpenAI model, MIT; converted artifact provenance retained below |

The converted model is pinned to `dropbox-dash/faster-whisper-large-v3-turbo`,
revision `0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf`. The weight artifact is
1,617,884,929 bytes, SHA-256
`e76620f83d5f5b69efd3d87e3dc180c1bd21df9fbebacfd4335e5e1efcc018da`.
Its model card is downloaded with the artifact. Checksums establish file identity
and integrity, not the absence of malicious code or model/parser flaws.

PyPA pip-audit reported no known vulnerabilities for the tested locks after
updating the original compatibility prototype. Re-run audits as advisories
change. Native vendor libraries, OS backports, drivers and hypervisors require
their own maintenance; a Python advisory scan does not audit those components.
Do not redistribute a packaged runtime or model by assuming this repository's
license covers downloaded third-party contents.
