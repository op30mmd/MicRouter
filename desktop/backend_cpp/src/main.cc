// MicRouter C++ backend entry point.
//
// Behaviour matches `python desktop/backend/backend.py`:
//   * bind 127.0.0.1:5000 for the Flutter UI (newline-delimited JSON)
//   * enumerate output devices on startup
//   * forward to the Android app on :6000 via `adb forward`
#include "core/backend.h"

int main() {
  mr::BackendServer server;
  return server.Run();
}
