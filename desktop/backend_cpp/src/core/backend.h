// ============================================================================
//  MicRouter C++ backend — core declarations.
// ============================================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace mr {

// ---- Audio devices ---------------------------------------------------------

// Lightweight handle around a PortAudio output device.
class AudioOutput {
 public:
  AudioOutput() = default;
  ~AudioOutput() = default;

  struct Device {
    int index = -1;      // PortAudio device index (-2 == our virtual mic marker)
    std::string name;
  };

  // Enumerates output devices, mirroring the priority logic in
  // desktop/backend/backend.py::_scan_devices (WASAPI > MME/DirectSound on
  // Windows; PulseAudio > ALSA on Linux, plus a synthetic virtual-mic entry).
  std::vector<Device> ListDevices();

  // Opens a 1-channel, int16 output stream at `device_index`.
  int Open(int device_index, int sample_rate, int frames_per_buffer);

  // Thread-safe write of a complete int16 chunk.
  int Write(const int16_t* data, int frames);

  void Close();

 private:
  void* stream_ = nullptr;   // PaStream*
  int sample_rate_ = 0;
};

// ---- Wire protocol ---------------------------------------------------------

// Outcome of a socket read.
enum class ReadStatus {
  kOk,      // complete
  kClosed,  // peer closed the connection
  kTimeout, // receive timeout elapsed (connection still open)
};

// Big-endian 32-bit read/write helpers (the Android app uses DataInputStream /
// DataOutputStream, i.e. network byte order, for the handshake and framing).
ReadStatus ReadInt32BE(int fd, int32_t* out);
ReadStatus ReadExactly(int fd, uint8_t* dst, size_t n);
bool WriteInt32BE(int fd, int32_t value);
bool WriteAll(int fd, const void* data, size_t n);
void SetReceiveTimeout(int fd, int seconds);

// ---- Backend ---------------------------------------------------------------

// Replicates desktop/backend/backend.py's UI socket server and audio pipeline.
class BackendServer {
 public:
  BackendServer();
  ~BackendServer();

  // Blocks in the accept loop; returns 0 on clean shutdown.
  int Run();

 private:
  struct Impl;
  Impl* impl_;
};

}  // namespace mr
