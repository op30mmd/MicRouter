#include "plugins/rnnoise.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>

#if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  include <windows.h>
#else
#  include <dlfcn.h>
#  include <link.h>
#  include <cstdio>
#  include <string>
#  include <vector>
#endif

namespace mr {

// ---------------------------------------------------------------------------
// Function-pointer table that mirrors the classic RNNoise C API (as used by
// desktop/backend/denoiser.py).  Resolved at runtime rather than linked.
// ---------------------------------------------------------------------------
struct RnnoiseEngine::Api {
  using CreateFn = void* (*)(const void* model);
  using DestroyFn = void (*)(void* state);
  using ProcessFn = float (*)(void* state, float* out, const float* in);

  CreateFn create = nullptr;
  DestroyFn destroy = nullptr;
  ProcessFn process = nullptr;
};

namespace {

void* GetSymbol(void* handle, const char* symbol) {
#if defined(_WIN32)
  return reinterpret_cast<void*>(
      GetProcAddress(static_cast<HMODULE>(handle), symbol));
#else
  return dlsym(handle, symbol);
#endif
}

// dlsym() into the process itself (RTLD_DEFAULT) does not reliably resolve
// symbols provided by a non-exported dynamically linked library on Linux, so
// for the undecorated binary we look the library up directly in /proc/self/maps.
// NOTE: this is Linux-specific and is only a fallback path.
#if !defined(_WIN32)
bool FindAndDlopenMapped(const std::string& needle, void** out_handle) {
  FILE* f = fopen("/proc/self/maps", "r");
  if (!f) return false;
  constexpr size_t kLineMax = 1024;
  char line[kLineMax];
  std::string found;
  while (fgets(line, sizeof(line), f)) {
    std::string l(line);
    if (l.find(needle) != std::string::npos) {
      // The mapped path is the last whitespace-separated token.
      std::vector<std::string> toks;
      std::string cur;
      for (char c : l) {
        if (c == ' ' || c == '\t') {
          if (!cur.empty()) { toks.push_back(cur); cur.clear(); }
        } else {
          cur += c;
        }
      }
      if (!cur.empty()) toks.push_back(cur);
      if (!toks.empty()) {
        std::string path = toks.back();
        if (path.find('[') == std::string::npos && !path.empty() && path[0] == '/') {
          // Only accept absolute filesystem paths (skip "[heap]", "[vdso]", ...).
          found = path;
          break;
        }
      }
    }
  }
  fclose(f);
  if (found.empty()) return false;
  void* h = dlopen(found.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!h) return false;
  *out_handle = h;
  return true;
}
#endif

}  // namespace

RnnoiseEngine::~RnnoiseEngine() { Destroy(); }

bool RnnoiseEngine::Init() {
  // Operator precedence: build the Api object first (it has a default
  // constructor), then try to resolve symbols from each candidate source.
  auto* api = new Api();
  std::vector<void*> handles;

  // Failure helper: roll back any handles opened so far and release the Api.
  auto fail = [&]() {
    Destroy();
    delete api;
    api_ = nullptr;
    return false;
  };

#if !defined(_WIN32)
  // (1) Any librnnoise the dynamic linker has already mapped — this covers the
  //     case where the distro package (libname "librnnoise.so.x") is present
  //     but, before glibc 2.34, dlopen bypassed hwcaps and missed it.
  if (FindAndDlopenMapped("librnnoise.so", &handle_)) {
    handles.push_back(handle_);
  }

  // (2) dlopen by soname.
  if (api->create == nullptr) {
    handle_ = nullptr;
    void* h = dlopen("librnnoise.so.0", RTLD_NOW | RTLD_LOCAL);
    if (!h) h = dlopen("librnnoise.so", RTLD_NOW | RTLD_LOCAL);
    if (h) handles.push_back(static_cast<void*>(h));
  }
#else
  // Windows: an rnnoise.dll shipped next to the executable or the bundled
  // native library.  Matches the loader order in denoiser.py.
  char dll_path[MAX_PATH];
  if (GetModuleFileNameA(nullptr, dll_path, MAX_PATH) > 0) {
    std::string dir(dll_path);
    size_t slash = dir.find_last_of("\\/");
    if (slash != std::string::npos) {
      dir = dir.substr(0, slash + 1);
      void* h = LoadLibraryA((dir + "rnnoise.dll").c_str());
      if (h) handles.push_back(h);
    }
  }
#endif

  for (void* h : handles) {
    if (!h) continue;
    Api::CreateFn c = reinterpret_cast<Api::CreateFn>(GetSymbol(h, "rnnoise_create"));
    Api::DestroyFn d = reinterpret_cast<Api::DestroyFn>(GetSymbol(h, "rnnoise_destroy"));
    Api::ProcessFn p = reinterpret_cast<Api::ProcessFn>(GetSymbol(h, "rnnoise_process_frame"));
    if (c && d && p) {
      api->create = c;
      api->destroy = d;
      api->process = p;
      handle_ = h;
      break;
    }
  }

  if (api->create == nullptr) {
    if (!handles.empty()) handle_ = handles.front();
    return fail();
  }

  // For any handle we ended up using, the rest remain open and are harmless
  // (the OS reference-counts them; Destroy() closes only the selected one).
  // Prevent a concurrent Destroy() / stream thread from racing Init().
  static std::mutex state_mutex;
  std::lock_guard<std::mutex> lock(state_mutex);

  state_ = api->create(nullptr);
  if (state_ == nullptr) return fail();

  api_ = api;
  return true;
}

bool RnnoiseEngine::ProcessInt16(int16_t* audio, int frame_samples) {
  if (!api_ || !state_ || !audio || frame_samples <= 0) return false;

  // rnnoise_process_frame() consumes exactly one 480-sample frame.
  constexpr int kFrame = kDenoiseFrameSamples;
  float in[kFrame];
  float out[kFrame];
  for (int i = 0; i < kFrame; ++i) {
    in[i] = static_cast<float>(audio[i]) / 32768.0f;
  }
  api_->process(state_, out, in);
  for (int i = 0; i < kFrame; ++i) {
    float v = out[i] * 32768.0f;
    if (v > 32767.0f) v = 32767.0f;
    if (v < -32768.0f) v = -32768.0f;
    audio[i] = static_cast<int16_t>(v);
  }
  return true;
}

void RnnoiseEngine::Destroy() {
  if (api_ && state_) {
    api_->destroy(state_);
  }
  state_ = nullptr;
  api_ = nullptr;
  if (handle_) {
#if defined(_WIN32)
    FreeLibrary(static_cast<HMODULE>(handle_));
#else
    dlclose(handle_);
#endif
    handle_ = nullptr;
  }
}

}  // namespace mr
