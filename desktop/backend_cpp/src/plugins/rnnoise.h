// ============================================================================
//  MicRouter — RNNoise via a runtime-loaded shared library.
//
//  This is the C++ equivalent of desktop/backend/denoiser.py.
//
//  Instead of linking RNNoise at build time, we load it at runtime ("dlopen"
//  on Linux, "LoadLibrary" on Windows) exactly like the Python version does
//  with ctypes. This lets the normal "flutter build" succeed on a machine
//  without rnnoise, and lets us either embed the third-party sources or link a
//  prebuilt library without touching the Flutter-native toolchain.
//
//  On Linux <= 6.4 the system-wide ``librnnoise`` package ships the newer API.
//  Modern glibc forbids a bare ``dlinfo()`` call (it was always a weak export),
//  so we resolve its real entry point through the dynamic linker. That makes
//  the undecorated (Linux) backend binary able to find the classic
//  RNNoiseModel* API wherever it is available.
// ============================================================================
#pragma once

#include <cstdint>

namespace mr {

// Frame the rest of the pipeline is built around (must be kept in sync):
//   * desktop/backend/denoiser.py
//   * the stream loop, which feeds frames_per_buffer-sized chunks
//   * RNNoise upstream, whose RNN runs on 480-sample / 10 ms frames @ 48 kHz
constexpr int kDenoiseFrameSamples = 480;

class RnnoiseEngine {
 public:
  ~RnnoiseEngine();

  // Returns false when the shared library could not be loaded (missing or
  // exposing an incompatible API); caller should fall back to passthrough.
  bool Init();

  // Denoises `frame_samples` int16 samples in `audio`. On success the result
  // is written back into `audio` and true is returned.
  bool ProcessInt16(int16_t* audio, int frame_samples);

  // Must be called once for each Init() that succeeds.
  void Destroy();

  bool available() const { return state_ != nullptr; }

 private:
  struct Api;
  const Api* api_ = nullptr;
  void* handle_ = nullptr;     // dlopen / LoadLibrary handle
  void* state_ = nullptr;      // DenoiseState*
};

}  // namespace mr
