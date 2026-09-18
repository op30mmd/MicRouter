import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';
import 'package:window_manager/window_manager.dart';

import 'backend_controller.dart';
import 'fluent_shell.dart';
import 'material_shell.dart';

// Re-export the shared controller and both shells so existing imports
// (`package:microuter_pc/main.dart` exposing `BackendController` / `MyApp`)
// keep working — notably `test/widget_test.dart`.
export 'backend_controller.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();

  WindowOptions windowOptions = const WindowOptions(
    size: Size(800, 600),
    center: true,
    backgroundColor: Color(0x00000000),
    skipTaskbar: false,
    titleBarStyle: TitleBarStyle.normal,
  );

  windowManager.waitUntilReadyToShow(windowOptions, () async {
    await windowManager.show();
    await windowManager.focus();
  });

  await windowManager.setPreventClose(true);

  runApp(
    ChangeNotifierProvider(
      create: (_) => BackendController(),
      child: const MyApp(),
    ),
  );
}

/// Platform-aware root.
///
/// - Windows → [FluentMicRouterApp] (Fluent UI, see `fluent_shell.dart`)
/// - Linux / others → [MaterialMicRouterApp] (Material/Yaru, unchanged)
class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with WindowListener {
  @override
  void initState() {
    super.initState();
    windowManager.addListener(this);
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    super.dispose();
  }

  @override
  void onWindowClose() async {
    // Every step is timeout-guarded and destroy() is guaranteed: with
    // preventClose, anything hanging before destroy() freezes the window.
    // The trailing exit() is unreachable on success — it only fires if
    // destroy() failed to take, so the app can never linger or wedge shut.
    final controller = Provider.of<BackendController>(context, listen: false);
    try {
      await controller.saveSettings().timeout(const Duration(seconds: 3));
    } catch (_) {}
    try {
      await controller.shutdown().timeout(const Duration(seconds: 3));
    } catch (_) {}
    try {
      await windowManager.destroy().timeout(const Duration(seconds: 3));
    } catch (_) {}
    exit(0);
  }

  @override
  Widget build(BuildContext context) {
    // Fluent UI is Windows-only by design; every other platform keeps the
    // existing Material/Yaru experience untouched.
    if (Platform.isWindows) {
      return const FluentMicRouterApp();
    }
    return const MaterialMicRouterApp();
  }
}
