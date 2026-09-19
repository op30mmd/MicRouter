// ============================================================================
//  MicRouter C++ backend — audio device enumeration / playback (PortAudio).
// ============================================================================
#include "core/backend.h"

#include <algorithm>
#include <cstring>

#include <portaudio.h>

namespace mr {

namespace {

struct PaInfo {
  int index;
  std::string name;
  int host_api;
  int max_output_channels;
};

}  // namespace

std::vector<AudioOutput::Device> AudioOutput::ListDevices() {
  std::vector<Device> result;

  // Linux virtual microphone entry, same as backend.py (-2 marker).
  // We detect Linux at compile time; the runtime flag is unnecessary because
  // this backend binary is always built for the platform it runs on.
#if defined(__linux__)
  Device virtual_mic;
  virtual_mic.index = -2;
  virtual_mic.name = "[Virtual Microphone] MicRouter";
  // Insert at the front so it is the default selection, as in backend.py.
#endif

  int host_api_count = Pa_GetHostApiCount();
  int target_api_index = -1;
#if defined(_WIN32)
  const char* target_api_name = "WASAPI";
#else
  const char* target_api_name = "PulseAudio";
#endif

  for (int i = 0; i < host_api_count; ++i) {
    const PaHostApiInfo* api = Pa_GetHostApiInfo(i);
    if (api && api->name &&
        std::strstr(api->name, target_api_name) != nullptr) {
      target_api_index = i;
      break;
    }
  }

#if !defined(_WIN32)
  // Fallback for Linux when PulseAudio is missing.
  if (target_api_index == -1) {
    for (int i = 0; i < host_api_count; ++i) {
      const PaHostApiInfo* api = Pa_GetHostApiInfo(i);
      if (api && api->name && std::strstr(api->name, "ALSA") != nullptr) {
        target_api_index = i;
        break;
      }
    }
  }
#endif

  int total = Pa_GetDeviceCount();
  std::vector<PaInfo> outs;
  for (int i = 0; i < total; ++i) {
    const PaDeviceInfo* dev = Pa_GetDeviceInfo(i);
    if (!dev) continue;
    if (dev->maxOutputChannels <= 0) continue;

    PaInfo info;
    info.index = i;
    info.name = dev->name ? dev->name : "";
    info.host_api = dev->hostApi;
    info.max_output_channels = dev->maxOutputChannels;
    outs.push_back(std::move(info));
  }

#if defined(_WIN32)
  // On Windows every endpoint is reported once per host API (MME,
  // DirectSound, WASAPI, WDM-KS), and the names differ per API: MME truncates
  // to 31 characters, WDM-KS uses its own wording, and MME/DirectSound add
  // "Microsoft Sound Mapper" / "Primary Sound Driver" pseudo-devices. Merging
  // by name therefore still shows each speaker three or four times. WASAPI
  // enumerates the real endpoints exactly once with their full names, and it
  // is the API we open anyway, so list only those when it is available.
  if (target_api_index != -1) {
    std::vector<Device> wasapi;
    for (const PaInfo& info : outs) {
      if (info.host_api != target_api_index) continue;
      bool exists = false;
      for (const auto& d : wasapi) {
        if (d.name == info.name) {
          exists = true;
          break;
        }
      }
      if (exists) continue;
      Device d;
      d.index = info.index;
      d.name = info.name;
      wasapi.push_back(std::move(d));
    }
    if (!wasapi.empty()) return wasapi;
  }
#endif

  // Prefer the low-latency host API device for a given name; otherwise keep
  // the first generic (MME/DirectSound/ALSA) device seen for that name.
  std::vector<Device> map;  // ordered mirror of backend.py's dict + keys()
  for (const PaInfo& info : outs) {
    bool replaced = false;
    if (info.host_api == target_api_index) {
      // Low-latency device: overwrite any earlier entry with the same name.
      for (auto& d : map) {
        if (d.name == info.name) {
          d.index = info.index;
          replaced = true;
          break;
        }
      }
    }
    if (!replaced) {
      bool exists = false;
      for (const auto& d : map) {
        if (d.name == info.name) {
          exists = true;
          break;
        }
      }
      if (!exists) {
        Device d;
        d.index = info.index;
        d.name = info.name;
        map.push_back(std::move(d));
      }
    }
  }

#if defined(__linux__)
  result.push_back(std::move(virtual_mic));
#endif
  for (auto& d : map) result.push_back(std::move(d));
  return result;
}

int AudioOutput::Open(int device_index, int sample_rate, int frames_per_buffer) {
  PaStreamParameters out_params;
  std::memset(&out_params, 0, sizeof(out_params));
  out_params.device = device_index;
  out_params.channelCount = 1;
  out_params.sampleFormat = paInt16;
  out_params.suggestedLatency =
      Pa_GetDeviceInfo(device_index)
          ? Pa_GetDeviceInfo(device_index)->defaultLowOutputLatency
          : 0.05;

  PaStream* stream = nullptr;
  PaError err = Pa_OpenStream(
      &stream, nullptr, &out_params, sample_rate,
      static_cast<unsigned long>(frames_per_buffer), paClipOff, nullptr,
      nullptr);
  if (err != paNoError) return err;

  err = Pa_StartStream(stream);
  if (err != paNoError) {
    Pa_CloseStream(stream);
    return err;
  }

  stream_ = stream;
  sample_rate_ = sample_rate;
  return paNoError;
}

int AudioOutput::Write(const int16_t* data, int frames) {
  if (!stream_) return -1;
  return Pa_WriteStream(static_cast<PaStream*>(stream_), data,
                        static_cast<unsigned long>(frames));
}

void AudioOutput::Close() {
  if (stream_) {
    Pa_CloseStream(static_cast<PaStream*>(stream_));
    stream_ = nullptr;
  }
}

}  // namespace mr
