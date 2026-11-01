"""Make the project-local NVIDIA wheel DLLs available before importing CT2."""
import os
import sys
import ctypes
from pathlib import Path

_dll_handles = []


def configure_gpu_dlls():
    if os.name != "nt":
        return
    scripts = Path(sys.prefix) / "Scripts"
    if (scripts / "msvcp140.dll").is_file():
        _dll_handles.append(os.add_dll_directory(str(scripts)))
        # ONNX Runtime needs a newer C++ runtime than this PC's system copy.
        # Preload the signed, application-local runtime before importing ONNX.
        _dll_handles.append(ctypes.WinDLL(str(scripts / "msvcp140.dll")))
    packages = Path(sys.prefix) / "Lib" / "site-packages" / "nvidia"
    directories = [packages / name / "bin" for name in ("cublas", "cudnn", "cuda_nvrtc")]
    for directory in directories:
        if directory.is_dir():
            os.environ["PATH"] = str(directory) + os.pathsep + os.environ.get("PATH", "")
            _dll_handles.append(os.add_dll_directory(str(directory)))
