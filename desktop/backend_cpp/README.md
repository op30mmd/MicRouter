# MicRouter C++ backend

A native, dependency-light C++17 port of [`desktop/backend/backend.py`](../backend/backend.py)
that behaves as a drop-in replacement for the Python engine.

The Flutter frontend already prefers this binary: it looks for
`backend/microuter_backend_cpp(.exe)` next to the executable before falling
back to the PyInstaller `microuter_backend(.exe)` or the `backend.py` script
(see `desktop/frontend/lib/main.dart` → `_startEmbeddedBackend()`).

## Why a port?

* **No Python runtime** on the target machine — smaller, faster startup, and a
  single self-contained distributable.
* **Lower per-chunk latency** — the core loop avoids repeated `numpy` copies
  and Python boxing on every 10 ms audio frame.
* The wire protocol is identical, so the Android app and the Flutter UI are
  unchanged.

## What was ported (1:1 from `backend.py`)

| Concern | Python | C++ |
|---|---|---|
| UI command server | JSON-over-TCP on `127.0.0.1:5000` | `src/core/backend.cc` (`HandleCommand`) |
| Device scan | PyAudio `_scan_devices` (WASAPI/MME, Pulse/ALSA, virtual mic) | `src/core/audio_device.cc` (`ListDevices`) |
| ADB forward | `subprocess adb forward` | `RunProcess` shim (`CreateProcessW` / `fork+exec`) |
| Handshake + framing | `struct.unpack('>i', ...)` | `src/core/wire.cc` (`ReadInt32BE`… big-endian) |
| Playback | PyAudio `paInt16`, 1 ch, 480-frame buffer | PortAudio `Pa_OpenStream`/`Pa_WriteStream` |
| Digital gain | `np.clip(audio * gain)` | inline clip in `AudioStreamThread` |
| RMS visualiser | `np.sqrt(mean(x**2)) / 2000` | `SimpleRingBuffer::Rms()` |
| RNNoise (AI denoise) | `ctypes` + `rnnoise_process_frame` | `src/plugins/rnnoise.cc` (runtime `dlopen`/`LoadLibrary`) |
| Linux virtual mic | `pactl load-module module-null-sink`… | `SetupLinuxVirtualMic` |

## Dependencies

Only **PortAudio** (the same thing PyAudio wraps). RNNoise is optional and
loaded at runtime — the search order matches `denoiser.py`:

* Linux: `/proc/self/maps` scan → `librnnoise.so.0` → `librnnoise.so`
* Windows: `rnnoise.dll` next to the executable

## Build

CMake tries pkg-config first; if PortAudio is missing it fetches and builds the
pinned `v19.7.0` release automatically via `FetchContent`.

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
# binary: build/microuter_backend
```

## Packaged release layout

Bundled next to the Flutter executable (see `.github/workflows/release-cpp-*.yml`):

```
backend/
  microuter_backend_cpp(.exe)   ← this binary (Flutter launches it)
  rnnoise.dll | librnnoise.so   ← optional AI denoiser
```
