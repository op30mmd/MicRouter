// ============================================================================
//  MicRouter C++ backend — a dependency-free port of desktop/backend/backend.py
//
//  Architecture (unchanged from the Python version):
//
//    [Flutter UI] --JSON/NL--> :5000 --control shim-- [this backend]
//        :6000 <--adb forward--> Android AudioService --framed int16 PCM-->
//        PortAudio output device (WASAPI/MME on Windows, Pulse/ALSA on Linux)
//
//  Kept intentionally close to the Python control flow so behaviour matches.
//  Notable differences / simplifications:
//    * JSON is formatted by hand (no third-party JSON library).
//    * RNNoise is loaded at runtime (see src/plugins/rnnoise.cc), exactly like
//      the Python version's ctypes loader.
//    * The Linux "virtual microphone" (pactl null-sink + remap-source) helper
//      functions are ported 1:1 below.
//    * Python's stdin "parent watchdog" (self-exit when Flutter closes the
//      pipe) is not re-implemented: Windows' Job Object + CREATE_NO_WINDOW
//      flag already guarantee child cleanup when the parent dies.
// ============================================================================
#include "core/backend.h"
#include "plugins/rnnoise.h"

#include <atomic>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  include <windows.h>
#else
#  include <arpa/inet.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/socket.h>
#  include <sys/wait.h>
#  include <unistd.h>
#endif

#include <portaudio.h>

namespace mr {

namespace {

constexpr int kFlutterPort = 5000;
constexpr int kAndroidPort = 6000;
constexpr int kReadySignal = 0x52454459;  // "REDY"
constexpr int kMaxChunk = 65536;          // sane upper bound for a frame
constexpr int kMaxRetries = 20;           // phone connect retries

using Sock = int;

#if defined(_WIN32)
using SocketLen = int;
constexpr Sock kInvalidSocket = INVALID_SOCKET;
#else
using SocketLen = socklen_t;
constexpr Sock kInvalidSocket = -1;
#endif

// ---- tiny JSON emitter -----------------------------------------------------
void EscapeJson(const std::string& in, std::string* out) {
  for (char c : in) {
    switch (c) {
      case '"': out->append("\\\""); break;
      case '\\': out->append("\\\\"); break;
      case '\n': out->append("\\n"); break;
      case '\r': out->append("\\r"); break;
      case '\t': out->append("\\t"); break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", c);
          out->append(buf);
        } else {
          out->push_back(c);
        }
    }
  }
}

std::string JsonObj(const char* type, const char* key, const std::string& value) {
  std::string s = "{\"type\":\"";
  s += type;
  s += "\",\"";
  s += key;
  s += "\":";
  s += value;
  s += "}\n";
  return s;
}

// Ring of recently written int16 samples for volume/visualiser reporting.
class SimpleRingBuffer {
 public:
  void Push(const int16_t* data, size_t frames) {
    for (size_t i = 0; i < frames; ++i) {
      buf_[pos_] = data[i];
      pos_ = (pos_ + 1) % kSize;
      if (count_ < kSize) count_++;
    }
  }

  float Rms() const {
    if (count_ == 0) return 0.0f;
    double acc = 0.0;
    for (size_t i = 0; i < count_; ++i) {
      acc += static_cast<double>(buf_[i]) * buf_[i];
    }
    return static_cast<float>(std::sqrt(acc / count_));
  }

  void Clear() {
    count_ = 0;
    pos_ = 0;
  }

 private:
  static constexpr size_t kSize = 4096;
  int16_t buf_[kSize] = {};
  size_t pos_ = 0;
  size_t count_ = 0;
};

// ---- command shim: runs a process and captures stdout ----------------------
struct RunResult {
  bool ok = false;
  std::string out;
  std::string err;
};

RunResult RunProcess(const std::vector<std::string>& argv) {
  RunResult r;
#if defined(_WIN32)
  std::string cmdline;
  for (const auto& a : argv) {
    if (!cmdline.empty()) cmdline += ' ';
    cmdline += a;
  }
  HANDLE rd, wr;
  SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
  if (!CreatePipe(&rd, &wr, &sa, 0)) return r;
  SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);

  STARTUPINFOA si{};
  si.cb = sizeof(si);
  si.dwFlags = STARTF_USESTDHANDLES;
  si.hStdOutput = wr;
  si.hStdError = wr;

  PROCESS_INFORMATION pi{};
  std::vector<char> cmd(cmdline.begin(), cmdline.end());
  cmd.push_back('\0');
  if (CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
                     nullptr, nullptr, &si, &pi)) {
    CloseHandle(wr);
    char buf[4096];
    DWORD n = 0;
    while (ReadFile(rd, buf, sizeof(buf), &n, nullptr) && n > 0) {
      r.out.append(buf, n);
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    r.ok = (code == 0);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
  }
  CloseHandle(rd);
  return r;
#else
  int pfd[2];
  if (pipe(pfd) != 0) return r;
  pid_t pid = fork();
  if (pid < 0) {
    close(pfd[0]);
    close(pfd[1]);
    return r;
  }
  if (pid == 0) {
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    close(pfd[0]);
    close(pfd[1]);
    std::vector<char*> args;
    for (const auto& a : argv) args.push_back(const_cast<char*>(a.c_str()));
    args.push_back(nullptr);
    execvp(args[0], args.data());
    _exit(127);
  }
  close(pfd[1]);
  char buf[4096];
  ssize_t n;
  while ((n = read(pfd[0], buf, sizeof(buf))) > 0) {
    r.out.append(buf, static_cast<size_t>(n));
  }
  close(pfd[0]);
  int status = 0;
  waitpid(pid, &status, 0);
  r.ok = WIFEXITED(status) && WEXITSTATUS(status) == 0;
  return r;
#endif
}

}  // namespace

// ===========================================================================
// Impl
// ===========================================================================
struct BackendServer::Impl {
  Sock server_socket = kInvalidSocket;
  Sock client_socket = kInvalidSocket;

  std::atomic<bool> is_streaming{false};
  std::thread audio_thread;

  AudioOutput audio;

  // Protected by `mutex` when mutated; the audio thread snapshots what it
  // needs at start and otherwise works on local copies.
  std::vector<AudioOutput::Device> devices;
  int default_device_index = -1;

  std::atomic<float> current_gain{1.0f};
  std::atomic<bool> use_rnnoise{false};
  // Denoiser, guarded by rnnoise_mutex (toggled from the UI thread while the
  // audio thread may be mid-frame).
  RnnoiseEngine rnnoise;
  std::mutex rnnoise_mutex;

  std::mutex mutex;        // guards devices / client_socket bookkeeping
  std::mutex send_mutex;   // serialises full JSON frames on the UI socket

  SimpleRingBuffer volume_ring;

  // Linux virtual-microphone bookkeeping (PulseAudio module ids).
  std::vector<std::string> linux_modules;

  void InitializeAudio();
  std::string ListDeviceNames();

  void SendJson(const std::string& message);
  void SendMessage(const char* type, const std::string& message);
  void SendVolume(float value);

  void HandleCommand(const std::string& request);
  void StartStreaming(const std::string& device_name, int port);
  void ToggleRnnoise(bool enable);

  bool SetupAdb(int port);
  bool SetupLinuxVirtualMic(int sample_rate, int* out_index);
  void CleanupLinuxVirtualMic();

  void AudioStreamThread(const std::string& device_name, int port);
};

void BackendServer::Impl::InitializeAudio() {
  PaError err = Pa_Initialize();
  if (err != paNoError) {
    std::fprintf(stderr, "[C++] PortAudio init failed: %s\n", Pa_GetErrorText(err));
    std::fflush(stderr);
  }
  std::lock_guard<std::mutex> lock(mutex);
  devices = audio.ListDevices();
  // backend.py: when a requested device name is missing it falls back to
  // ``p.get_default_output_device_info()["index"]`` — Pa_GetDefaultOutputDevice().
  default_device_index = Pa_GetDefaultOutputDevice();
  if (default_device_index < 0) {
    // No default device (e.g. headless/CI): pick the first real output if any.
    for (const auto& d : devices) {
      if (d.index >= 0) {
        default_device_index = d.index;
        break;
      }
    }
  }
}

std::string BackendServer::Impl::ListDeviceNames() {
  std::string payload = "[";
  bool first = true;
  for (const auto& d : devices) {
    if (!first) payload += ',';
    std::string enc;
    EscapeJson(d.name, &enc);
    payload += '"';
    payload += enc;
    payload += '"';
    first = false;
  }
  payload += ']';
  return payload;
}

void BackendServer::Impl::SendJson(const std::string& message) {
  // Snapshot the UI socket under the bookkeeping mutex first, so callers that
  // already hold `mutex` (e.g. get_devices) never re-enter it.
  Sock fd;
  {
    std::lock_guard<std::mutex> b(mutex);
    fd = client_socket;
  }
  if (fd == kInvalidSocket) return;

  std::lock_guard<std::mutex> lock(send_mutex);
  const uint8_t* p = reinterpret_cast<const uint8_t*>(message.data());
  size_t n = message.size();
  while (n > 0) {
    int s = send(fd, reinterpret_cast<const char*>(p), static_cast<int>(n), 0);
    if (s <= 0) break;
    p += s;
    n -= static_cast<size_t>(s);
  }
}

void BackendServer::Impl::SendMessage(const char* type, const std::string& message) {
  // backend.py uses "payload" for status/devices and "message" for log/error.
  const char* key = "message";
  if (std::strcmp(type, "status") == 0) key = "payload";
  std::string enc;
  EscapeJson(message, &enc);
  SendJson(JsonObj(type, key, "\"" + enc + "\""));
}

void BackendServer::Impl::SendVolume(float value) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%.4f", value);
  SendJson(JsonObj("volume", "value", buf));
}

// ---------------------------------------------------------------------------
// ADB + Linux virtual mic helpers (mapped 1:1 from backend.py)
// ---------------------------------------------------------------------------
bool BackendServer::Impl::SetupAdb(int port) {
  SendMessage("log", "[*] Setting up ADB...");
  char buf[64];
  std::snprintf(buf, sizeof(buf), "tcp:%d", port);
  RunProcess({"adb", "forward", "--remove", std::string(buf)});
  RunResult r = RunProcess({"adb", "forward", std::string(buf), std::string(buf)});
  if (!r.ok) {
    SendMessage("error", "ADB Error: " + (r.err.empty() ? r.out : r.err));
    return false;
  }
  return true;
}

bool BackendServer::Impl::SetupLinuxVirtualMic(int sample_rate, int* out_index) {
#if defined(__linux__)
  CleanupLinuxVirtualMic();

  char rate[32];
  std::snprintf(rate, sizeof(rate), "%d", sample_rate);
  {
    RunResult r1 = RunProcess(
        {"pactl", "load-module", "module-null-sink", "sink_name=microuter_sink",
         "sink_properties=\"device.description='MicRouter Internal Sink'\"",
         std::string("rate=") + rate, "channels=1"});
    if (r1.ok && !r1.out.empty()) {
      std::string line = r1.out;
      while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
      linux_modules.push_back(line);
    }
  }
  {
    RunResult r2 = RunProcess(
        {"pactl", "load-module", "module-remap-source",
         "master=microuter_sink.monitor", "source_name=microuter_source",
         "source_properties=\"device.description='MicRouter Virtual Microphone' device.class='audio' device.icon_name='audio-input-microphone'\"",
         "channels=1"});
    if (r2.ok && !r2.out.empty()) {
      std::string line = r2.out;
      while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
      linux_modules.push_back(line);
    }
  }

  // Give PulseAudio a moment to register the new source.
  std::this_thread::sleep_for(std::chrono::milliseconds(1000));

  // Refresh device enumeration (thread-safe replacement for backend.py's
  // terminate + re-init + re-scan).
  {
    std::lock_guard<std::mutex> lock(mutex);
    devices = audio.ListDevices();
    for (const auto& d : devices) {
      std::string n = d.name;
      for (auto& c : n) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
      if (n.find("microuter") != std::string::npos ||
          n.find("null sink") != std::string::npos) {
        *out_index = d.index;
        return true;
      }
    }
  }
  return false;
#else
  (void)sample_rate;
  (void)out_index;
  SendMessage("error", "Virtual Microphone is only supported on Linux.");
  return false;
#endif
}

void BackendServer::Impl::CleanupLinuxVirtualMic() {
#if defined(__linux__)
  // (1) scan `pactl list short modules` for anything "microuter"-related.
  RunResult list = RunProcess({"pactl", "list", "short", "modules"});
  std::string s = list.out;
  size_t start = 0;
  while (start < s.size()) {
    size_t eol = s.find('\n', start);
    if (eol == std::string::npos) eol = s.size();
    std::string line = s.substr(start, eol - start);
    std::string lower = line;
    for (auto& c : lower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    if (lower.find("microuter") != std::string::npos) {
      size_t sp = line.find_first_of(" \t");
      std::string id = (sp == std::string::npos) ? line : line.substr(0, sp);
      if (!id.empty()) RunProcess({"pactl", "unload-module", id});
    }
    start = eol + 1;
  }

  // (2) legacy/fallback names.
  RunProcess({"pactl", "unload-module", "module-remap-source"});
  RunProcess({"pactl", "unload-module", "module-null-sink"});

  // (3) tracked ids, newest first.
  for (auto it = linux_modules.rbegin(); it != linux_modules.rend(); ++it) {
    RunProcess({"pactl", "unload-module", *it});
  }
  linux_modules.clear();
#endif
}

// ---------------------------------------------------------------------------
// Control command handling
// ---------------------------------------------------------------------------
void BackendServer::Impl::HandleCommand(const std::string& request) {
  // Minimal JSON handling: {"command": "x", ...}.
  std::string cmd;
  size_t key = request.find("\"command\"");
  if (key != std::string::npos) {
    size_t colon = request.find(':', key);
    size_t q1 = request.find('"', colon);
    if (q1 != std::string::npos) {
      size_t q2 = request.find('"', q1 + 1);
      if (q2 != std::string::npos) cmd = request.substr(q1 + 1, q2 - q1 - 1);
    }
  }

  auto find_string = [&](const char* name, std::string* out) -> bool {
    std::string k = std::string("\"") + name + "\"";
    size_t pos = request.find(k);
    if (pos == std::string::npos) return false;
    size_t q1 = request.find('"', pos + k.size());
    if (q1 == std::string::npos) return false;
    size_t q2 = request.find('"', q1 + 1);
    if (q2 == std::string::npos) return false;
    *out = request.substr(q1 + 1, q2 - q1 - 1);
    return true;
  };
  auto find_number = [&](const char* name, double* out) -> bool {
    std::string k = std::string("\"") + name + "\"";
    size_t pos = request.find(k);
    if (pos == std::string::npos) return false;
    size_t colon = request.find(':', pos);
    if (colon == std::string::npos) return false;
    char* end = nullptr;
    *out = std::strtod(request.c_str() + colon + 1, &end);
    return end != request.c_str() + colon + 1;
  };
  auto find_bool = [&](const char* name, bool* out) -> bool {
    std::string k = std::string("\"") + name + "\"";
    size_t pos = request.find(k);
    if (pos == std::string::npos) return false;
    size_t colon = request.find(':', pos);
    if (colon == std::string::npos) return false;
    std::string tail = request.substr(colon + 1);
    if (tail.find("true") != std::string::npos) {
      *out = true;
    } else if (tail.find("false") != std::string::npos) {
      *out = false;
    } else {
      return false;
    }
    return true;
  };

  if (cmd == "get_devices") {
    std::string payload;
    {
      std::lock_guard<std::mutex> lock(mutex);
      devices = audio.ListDevices();
      payload = ListDeviceNames();
    }
    SendJson(JsonObj("devices", "payload", payload));
    return;
  }

  if (cmd == "set_gain") {
    double val = 1.0;
    if (find_number("value", &val)) current_gain = static_cast<float>(val);
    return;
  }

  if (cmd == "toggle_rnnoise") {
    bool enable = false;
    if (find_bool("value", &enable)) ToggleRnnoise(enable);
    return;
  }

  if (cmd == "start") {
    std::string device_name;
    find_string("device_name", &device_name);
    double port = kAndroidPort;
    find_number("port", &port);
    if (!is_streaming) StartStreaming(device_name, static_cast<int>(port));
    return;
  }

  if (cmd == "stop") {
    is_streaming = false;
    return;
  }
}

void BackendServer::Impl::ToggleRnnoise(bool enable) {
  std::lock_guard<std::mutex> lock(rnnoise_mutex);
  if (enable) {
    if (!rnnoise.available() && !rnnoise.Init()) {
      SendMessage("error", "RNNoise Error: library not found or incompatible");
      use_rnnoise = false;
      return;
    }
    use_rnnoise = true;
    SendMessage("log", "[*] AI Denoising Enabled");
  } else {
    use_rnnoise = false;
    if (rnnoise.available()) rnnoise.Destroy();
    SendMessage("log", "[*] AI Denoising Disabled");
  }
}

// ---------------------------------------------------------------------------
// Audio stream thread
// ---------------------------------------------------------------------------
void BackendServer::Impl::StartStreaming(const std::string& device_name, int port) {
  is_streaming = true;
  audio_thread = std::thread([this, device_name, port] {
    AudioStreamThread(device_name, port);
  });
}

void BackendServer::Impl::AudioStreamThread(const std::string& device_name, int port) {
  bool stream_open = false;
  int sock = kInvalidSocket;
  int sample_rate = 0;
  int device_index = -1;  // declared up front so `goto cleanup` is legal

  // Local snapshot of the device list (the UI may re-scan concurrently).
  std::vector<AudioOutput::Device> local_devices;
  {
    std::lock_guard<std::mutex> lock(mutex);
    local_devices = devices;
  }

  if (!SetupAdb(port)) {
    is_streaming = false;
    SendMessage("status", "failed");
    return;
  }

  SendMessage("status", "connecting");

  // --- CONNECTION RETRY LOOP -----------------------------------------------
  bool connected = false;
  int attempts = 0;
  while (is_streaming && !connected && attempts < kMaxRetries) {
    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock == kInvalidSocket) break;
    int yes = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&yes), sizeof(yes));
    int rcvbuf = 4096;
    setsockopt(sock, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcvbuf), sizeof(rcvbuf));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port & 0xffff));
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    if (connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0) {
      connected = true;
    } else {
      attempts++;
      if (attempts % 2 == 0) {
        char msg[64];
        std::snprintf(msg, sizeof(msg), "[*] Waiting for phone... (%d/%d)", attempts, kMaxRetries);
        SendMessage("log", msg);
      }
#if defined(_WIN32)
      closesocket(sock);
#else
      close(sock);
#endif
      sock = kInvalidSocket;
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
  }

  if (!connected) {
    SendMessage("error", "Could not connect to phone. Is the app running?");
    is_streaming = false;
    SendMessage("status", "stopped");
    SendVolume(0.0f);
    return;
  }

  SendMessage("log", "[*] Connected! Performing handshake...");

  // ================= HANDSHAKE (mirrors backend.py) ========================
  // backend.py: sock.settimeout(5) for the handshake.
  SetReceiveTimeout(sock, 5);

  int32_t sr = 0;
  if (ReadInt32BE(sock, &sr) != ReadStatus::kOk) {
    SendMessage("error", "Handshake failed (Sample Rate)");
    goto cleanup;
  }
  if (sr <= 0 || sr > 192000) {
    SendMessage("error", "Invalid sample rate: " + std::to_string(sr));
    goto cleanup;
  }
  sample_rate = sr;
  {
    char msg[64];
    std::snprintf(msg, sizeof(msg), "[*] Sample Rate: %d Hz", sample_rate);
    SendMessage("log", msg);
  }

  if (!WriteInt32BE(sock, sr)) {
    SendMessage("error", "Handshake failed (Ack)");
    goto cleanup;
  }
  {
    int32_t ready = 0;
    if (ReadInt32BE(sock, &ready) != ReadStatus::kOk) {
      SendMessage("error", "Handshake failed (Ready Signal)");
      goto cleanup;
    }
    if (ready != kReadySignal) {
      char msg[96];
      std::snprintf(msg, sizeof(msg), "Invalid ready signal: 0x%x", ready);
      SendMessage("error", msg);
      goto cleanup;
    }
  }
  SendMessage("log", "[*] Handshake complete!");

  // --- AUDIO DEVICE SETUP --------------------------------------------------
  for (const auto& d : local_devices) {
    if (d.name == device_name) {
      device_index = d.index;
      break;
    }
  }

  if (device_index == -2) {  // Linux virtual mic marker
    SendMessage("log", "[*] Setting up Virtual Microphone...");
    if (!SetupLinuxVirtualMic(sample_rate, &device_index)) {
      SendMessage("error", "Failed to setup virtual microphone. Is pactl installed?");
      goto cleanup;
    }
  }

  if (device_index < 0) device_index = default_device_index;
  if (device_index < 0) {
    SendMessage("error", "No output device available.");
    goto cleanup;
  }

  {
    PaError err = audio.Open(device_index, sample_rate, 480);
    if (err != paNoError) {
      SendMessage("error", std::string("Failed to open output device: ") + Pa_GetErrorText(err));
      goto cleanup;
    }
    stream_open = true;
  }

  SendMessage("status", "running");

  // ================= STREAM LOOP (mirrors backend.py) ======================
  {
    const size_t frame_samples = kDenoiseFrameSamples;

    // Denoiser accumulation buffer: hold samples until a full frame is ready.
    std::vector<int16_t> rn_pending;
    std::vector<int16_t> output;

    int consecutive_errors = 0;
    const int max_consecutive_errors = 5;

    volume_ring.Clear();
    auto last_vol_time = std::chrono::steady_clock::now();
    float last_vol = 0.0f;

    // backend.py: sock.settimeout(10) before the stream loop so a "stop"
    // command is honoured within ~10s even if the phone goes silent.
    SetReceiveTimeout(sock, 10);

    while (is_streaming) {
      int32_t length = 0;
      // backend.py's _recv_exact() swallows timeouts and returns None for both
      // "closed" and "timeout"; the caller then logs and breaks. We mirror
      // that: any header read failure ends the stream (the 10s receive timeout
      // also guarantees a "stop" command is honoured within ~10s of silence).
      if (ReadInt32BE(sock, &length) != ReadStatus::kOk) {
        SendMessage("log", "[*] Connection closed by phone");
        break;
      }
      if (length <= 0 || length > kMaxChunk) {
        consecutive_errors++;
        if (consecutive_errors >= max_consecutive_errors) break;
        continue;
      }

      std::vector<int16_t> chunk(static_cast<size_t>(length) / 2);
      if (!chunk.empty()) {
        uint8_t* raw = reinterpret_cast<uint8_t*>(chunk.data());
        if (ReadExactly(sock, raw, static_cast<size_t>(length)) != ReadStatus::kOk) {
          break;
        }
      }
      consecutive_errors = 0;

      // --- PROCESS: optional RNNoise + gain --------------------------------
      int16_t* out = chunk.data();
      size_t out_count = chunk.size();

      if (use_rnnoise) {
        std::lock_guard<std::mutex> lock(rnnoise_mutex);
        if (rnnoise.available()) {
          // Append to the pending buffer, then denoise every full frame.
          rn_pending.insert(rn_pending.end(), chunk.begin(), chunk.end());
          output.clear();
          size_t nfull = rn_pending.size() / frame_samples;
          size_t base = 0;
          for (size_t f = 0; f < nfull; ++f) {
            rnnoise.ProcessInt16(rn_pending.data() + base, static_cast<int>(frame_samples));
            output.insert(output.end(), rn_pending.begin() + base,
                          rn_pending.begin() + base + frame_samples);
            base += frame_samples;
          }
          // Carry the remainder for the next chunk.
          rn_pending.erase(rn_pending.begin(), rn_pending.begin() + base);
          out = output.data();
          out_count = output.size();
        }
      }

      if (out_count > 0) {
        for (size_t i = 0; i < out_count; ++i) {
          float v = static_cast<float>(out[i]) * current_gain;
          if (v > 32767.0f) v = 32767.0f;
          if (v < -32768.0f) v = -32768.0f;
          out[i] = static_cast<int16_t>(v);
        }

        // Volume reporting (throttled to ~10 Hz).
        auto now = std::chrono::steady_clock::now();
        double ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_vol_time).count();
        if (ms >= 100.0) {
          volume_ring.Push(out, out_count);
          float rms = volume_ring.Rms();
          float normalized = std::min(rms / 2000.0f, 1.0f);
          if (normalized != last_vol) {
            SendVolume(normalized);
            last_vol = normalized;
          }
          last_vol_time = now;
        }

        if (stream_open) {
          // Pa_WriteStream blocks until all frames are consumed.
          audio.Write(out, static_cast<int>(out_count));
        }
      }
    }
  }

cleanup:
  if (stream_open) audio.Close();
  if (sock != kInvalidSocket) {
#if defined(_WIN32)
    closesocket(sock);
#else
    close(sock);
#endif
  }
#if defined(__linux__)
  if (!linux_modules.empty()) CleanupLinuxVirtualMic();
#endif

  is_streaming = false;
  SendMessage("status", "stopped");
  SendVolume(0.0f);
}

// ===========================================================================
// BackendServer
// ===========================================================================
BackendServer::BackendServer() : impl_(new Impl) {}

BackendServer::~BackendServer() {
  if (impl_) {
    impl_->is_streaming = false;
    if (impl_->audio_thread.joinable()) impl_->audio_thread.join();
    if (impl_->rnnoise.available()) impl_->rnnoise.Destroy();
    if (impl_->server_socket != kInvalidSocket) {
#if defined(_WIN32)
      closesocket(impl_->server_socket);
#else
      close(impl_->server_socket);
#endif
    }
    Pa_Terminate();
    delete impl_;
    impl_ = nullptr;
  }
}

int BackendServer::Run() {
  Impl& impl = *impl_;

#if defined(_WIN32)
  WSADATA wsa;
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
    std::fprintf(stderr, "[C++] WSAStartup failed\n");
    return 1;
  }
#endif

  impl.InitializeAudio();

  Sock srv = socket(AF_INET, SOCK_STREAM, 0);
  if (srv == kInvalidSocket) {
    std::fprintf(stderr, "[C++] socket() failed\n");
    return 1;
  }
  int yes = 1;
  setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons(kFlutterPort);
  if (bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    std::fprintf(stderr, "[C++] Port %d is busy. Is the app already running?\n", kFlutterPort);
    std::fflush(stderr);
    return 1;
  }
  if (listen(srv, 1) != 0) return 1;

  std::fprintf(stdout, "[*] C++ Backend listening on %d...\n", kFlutterPort);
  std::fflush(stdout);

  impl.server_socket = srv;

  for (;;) {
    sockaddr_in peer{};
    SocketLen plen = sizeof(peer);
    Sock client = accept(srv, reinterpret_cast<sockaddr*>(&peer), &plen);
    if (client == kInvalidSocket) continue;
    {
      std::lock_guard<std::mutex> lock(impl.mutex);
      impl.client_socket = client;
    }
    std::fprintf(stdout, "[*] UI Connected\n");
    std::fflush(stdout);

    std::string buffer;
    char buf[1024];
    while (impl.client_socket != kInvalidSocket) {
      int n = recv(client, buf, sizeof(buf), 0);
      if (n <= 0) {
        if (!impl.is_streaming && n == 0) {
          break;  // UI closed; streaming may still continue.
        }
        break;
      }
      buffer.append(buf, static_cast<size_t>(n));
      size_t nl;
      while ((nl = buffer.find('\n')) != std::string::npos) {
        std::string line = buffer.substr(0, nl);
        buffer.erase(0, nl + 1);
        if (!line.empty()) impl.HandleCommand(line);
      }
    }

    {
      std::lock_guard<std::mutex> lock(impl.mutex);
      if (impl.client_socket == client) impl.client_socket = kInvalidSocket;
    }
#if defined(_WIN32)
    closesocket(client);
#else
    close(client);
#endif
  }

#if defined(_WIN32)
  closesocket(srv);
  WSACleanup();
#else
  close(srv);
#endif
  return 0;
}

}  // namespace mr
