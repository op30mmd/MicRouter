#include "flutter_window.h"

#include <dwmapi.h>
#include <flutter/standard_method_codec.h>

#include <optional>

#include "flutter/generated_plugin_registrant.h"

namespace {

// DWM attributes introduced by Windows 11 SDKs; redefined so the runner
// also builds against older Windows 10 SDKs (the calls simply fail at
// runtime on systems that lack them, which the caller reports back).
#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif
#ifndef DWMWA_CAPTION_COLOR
#define DWMWA_CAPTION_COLOR 35
#endif
#ifndef DWMWA_SYSTEMBACKDROP_TYPE
#define DWMWA_SYSTEMBACKDROP_TYPE 38
#endif
#ifndef DWMWA_COLOR_DEFAULT
#define DWMWA_COLOR_DEFAULT 0xFFFFFFFF
#endif
#ifndef DWMWA_COLOR_NONE
#define DWMWA_COLOR_NONE 0xFFFFFFFE
#endif
constexpr int kBackdropAuto = 0;        // DWMSBT_AUTO
constexpr int kBackdropMainWindow = 2;  // DWMSBT_MAINWINDOW (Mica)

// Applies the Windows 11 Mica system backdrop to |window| (or removes it),
// and sets the caption to the app's light/dark theme.
//
// Mica needs the frame extended over the whole client area so DWM paints
// the backdrop beneath the Flutter view; Flutter then shows it wherever it
// leaves pixels transparent. With DWMWA_CAPTION_COLOR = NONE the native
// title bar becomes part of the same Mica sheet, as in Windows 11 apps.
//
// Returns whether Mica is active afterwards. Dart only makes its window
// background transparent on `true`; on older Windows the widget tree keeps
// painting the solid Mica fallback colour itself.
bool ApplyBackdrop(HWND window, bool mica, bool dark) {
  BOOL dark_mode = dark ? TRUE : FALSE;
  ::DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE, &dark_mode,
                          sizeof(dark_mode));

  if (!mica) {
    MARGINS none = {0, 0, 0, 0};
    ::DwmExtendFrameIntoClientArea(window, &none);
    int type = kBackdropAuto;
    ::DwmSetWindowAttribute(window, DWMWA_SYSTEMBACKDROP_TYPE, &type,
                            sizeof(type));
    COLORREF caption = DWMWA_COLOR_DEFAULT;
    ::DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, &caption,
                            sizeof(caption));
    return false;
  }

  MARGINS sheet = {-1, -1, -1, -1};
  if (FAILED(::DwmExtendFrameIntoClientArea(window, &sheet))) {
    return false;
  }
  int type = kBackdropMainWindow;
  if (FAILED(::DwmSetWindowAttribute(window, DWMWA_SYSTEMBACKDROP_TYPE, &type,
                                     sizeof(type)))) {
    // Pre-22H2 Windows: undo the glass sheet, keep the normal frame.
    MARGINS none = {0, 0, 0, 0};
    ::DwmExtendFrameIntoClientArea(window, &none);
    return false;
  }
  COLORREF caption = DWMWA_COLOR_NONE;
  ::DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, &caption,
                          sizeof(caption));
  return true;
}

bool BoolArg(const flutter::EncodableMap* args, const char* key,
             bool fallback) {
  if (!args) return fallback;
  auto it = args->find(flutter::EncodableValue(key));
  if (it == args->end()) return fallback;
  const bool* value = std::get_if<bool>(&it->second);
  return value ? *value : fallback;
}

}  // namespace

FlutterWindow::FlutterWindow(const flutter::DartProject& project)
    : project_(project) {}

FlutterWindow::~FlutterWindow() {}

bool FlutterWindow::OnCreate() {
  if (!Win32Window::OnCreate()) {
    return false;
  }

  RECT frame = GetClientArea();

  // The size here must match the window dimensions to avoid unnecessary surface
  // creation / destruction in the startup path.
  flutter_controller_ = std::make_unique<flutter::FlutterViewController>(
      frame.right - frame.left, frame.bottom - frame.top, project_);
  // Ensure that basic setup of the controller was successful.
  if (!flutter_controller_->engine() || !flutter_controller_->view()) {
    return false;
  }
  RegisterPlugins(flutter_controller_->engine());
  SetChildContent(flutter_controller_->view()->GetNativeWindow());

  // setBackdrop({mica: bool, dark: bool}) -> bool (Mica active).
  window_channel_ =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          flutter_controller_->engine()->messenger(), "microuter/window",
          &flutter::StandardMethodCodec::GetInstance());
  window_channel_->SetMethodCallHandler(
      [this](const flutter::MethodCall<flutter::EncodableValue>& call,
             std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>>
                 result) {
        if (call.method_name() != "setBackdrop") {
          result->NotImplemented();
          return;
        }
        const auto* args =
            std::get_if<flutter::EncodableMap>(call.arguments());
        bool active = ApplyBackdrop(GetHandle(), BoolArg(args, "mica", true),
                                    BoolArg(args, "dark", true));
        result->Success(flutter::EncodableValue(active));
      });

  flutter_controller_->engine()->SetNextFrameCallback([&]() {
    this->Show();
  });

  // Flutter can complete the first frame before the "show window" callback is
  // registered. The following call ensures a frame is pending to ensure the
  // window is shown. It is a no-op if the first frame hasn't completed yet.
  flutter_controller_->ForceRedraw();

  return true;
}

void FlutterWindow::OnDestroy() {
  if (flutter_controller_) {
    flutter_controller_ = nullptr;
  }

  Win32Window::OnDestroy();
}

LRESULT
FlutterWindow::MessageHandler(HWND hwnd, UINT const message,
                              WPARAM const wparam,
                              LPARAM const lparam) noexcept {
  // Give Flutter, including plugins, an opportunity to handle window messages.
  if (flutter_controller_) {
    std::optional<LRESULT> result =
        flutter_controller_->HandleTopLevelWindowProc(hwnd, message, wparam,
                                                      lparam);
    if (result) {
      return *result;
    }
  }

  switch (message) {
    case WM_FONTCHANGE:
      flutter_controller_->engine()->ReloadSystemFonts();
      break;
  }

  return Win32Window::MessageHandler(hwnd, message, wparam, lparam);
}
