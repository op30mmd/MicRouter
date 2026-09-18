import 'package:fluent_ui/fluent_ui.dart';
import 'package:provider/provider.dart';

import 'backend_controller.dart';

/// Fluent shell — Windows only.
///
/// Uses the Fluent Design System ([FluentApp], [NavigationView],
/// [ScaffoldPage], [Card], [InfoBar], [ComboBox], [ToggleSwitch], ...) so the
/// Windows app feels native. Linux keeps using the Material/Yaru shell in
/// `material_shell.dart`; `main.dart` picks this shell when
/// `Platform.isWindows` is true.
class FluentMicRouterApp extends StatelessWidget {
  const FluentMicRouterApp({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    return FluentApp(
      title: 'MicRouter PC',
      debugShowCheckedModeBanner: false,
      color: Colors.blue,
      themeMode: controller.isDarkMode ? ThemeMode.dark : ThemeMode.light,
      theme: FluentThemeData(
        brightness: Brightness.light,
        accentColor: Colors.blue,
      ),
      darkTheme: FluentThemeData(
        brightness: Brightness.dark,
        accentColor: Colors.blue,
      ),
      home: const FluentHomeScreen(),
    );
  }
}

class FluentHomeScreen extends StatefulWidget {
  const FluentHomeScreen({super.key});

  @override
  State<FluentHomeScreen> createState() => _FluentHomeScreenState();
}

class _FluentHomeScreenState extends State<FluentHomeScreen> {
  int _selectedIndex = 0;

  @override
  Widget build(BuildContext context) {
    return NavigationView(
      pane: NavigationPane(
        selected: _selectedIndex,
        onChanged: (index) => setState(() => _selectedIndex = index),
        // Expanded (not auto/compact) so Router/Settings labels always stay
        // visible in the 800x600 window, mirroring the Material rail.
        displayMode: PaneDisplayMode.expanded,
        header: const Padding(
          padding: EdgeInsets.only(left: 12, top: 12, bottom: 8),
          child: Text(
            'MicRouter',
            style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
          ),
        ),
        items: [
          PaneItem(
            icon: const Icon(FluentIcons.microphone),
            title: const Text('Router'),
            body: const FluentRouterView(),
          ),
          PaneItem(
            icon: const Icon(FluentIcons.settings),
            title: const Text('Settings'),
            body: const FluentSettingsView(),
          ),
        ],
        footerItems: [
          PaneItemSeparator(),
          PaneItem(
            icon: const Icon(FluentIcons.info),
            title: const Text('About'),
            body: const ScaffoldPage(
              header: PageHeader(title: Text('About')),
              content: Padding(
                padding: EdgeInsets.symmetric(horizontal: 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('MicRouter PC'),
                    SizedBox(height: 8),
                    Text(
                      'Route your Android microphone to Windows over USB with low latency.',
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class FluentRouterView extends StatefulWidget {
  const FluentRouterView({super.key});

  @override
  State<FluentRouterView> createState() => _FluentRouterViewState();
}

class _FluentRouterViewState extends State<FluentRouterView> {
  final ScrollController _logScrollController = ScrollController();

  @override
  void dispose() {
    _logScrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();
    final isRunning = controller.status == "running";
    final canStart = !isRunning && controller.selectedDevice != null;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScrollController.hasClients) {
        _logScrollController
            .jumpTo(_logScrollController.position.maxScrollExtent);
      }
    });

    return ScaffoldPage.scrollable(
      header: const PageHeader(title: Text('Dashboard')),
      children: [
        // Status — Fluent InfoBar mirrors the Material status pill.
        InfoBar(
          title: Text('STATUS: ${controller.status.toUpperCase()}'),
          severity:
              isRunning ? InfoBarSeverity.success : InfoBarSeverity.info,
        ),
        const SizedBox(height: 16),

        // Microphone level — Fluent ProgressBar driven by the same
        // volumeNotifier (~10 Hz) so only this subtree repaints.
        Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(FluentIcons.microphone),
                  const SizedBox(width: 8),
                  const Text('Microphone Level'),
                  const Spacer(),
                  ValueListenableBuilder<double>(
                    valueListenable: controller.volumeNotifier,
                    builder: (context, volume, _) {
                      final pct =
                          (volume.clamp(0.0, 1.0) * 100).toStringAsFixed(0);
                      return Text('$pct%');
                    },
                  ),
                ],
              ),
              const SizedBox(height: 12),
              ValueListenableBuilder<double>(
                valueListenable: controller.volumeNotifier,
                builder: (context, volume, _) {
                  return ProgressBar(
                    value: volume.clamp(0.0, 1.0) * 100,
                    strokeWidth: 8,
                  );
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // Transport controls.
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            FilledButton(
              onPressed: canStart ? controller.startStreaming : null,
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(FluentIcons.play),
                  SizedBox(width: 8),
                  Text('START ROUTING'),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Button(
              onPressed: isRunning ? controller.stopStreaming : null,
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(FluentIcons.stop),
                  SizedBox(width: 8),
                  Text('STOP'),
                ],
              ),
            ),
          ],
        ),
        if (controller.selectedDevice == null) ...[
          const SizedBox(height: 12),
          const InfoBar(
            title: Text('No output device selected'),
            content: Text('Pick a speaker in Settings to enable routing.'),
            severity: InfoBarSeverity.warning,
          ),
        ],
        const SizedBox(height: 24),

        // System logs — Fluent Expander keeps parity with the Material log box.
        Expander(
          initiallyExpanded: true,
          leading: const Icon(FluentIcons.command_prompt),
          header: const Text('System Logs'),
          content: SizedBox(
            height: 140,
            child: controller.logs.isEmpty
                ? const Text('No logs yet.')
                : ListView.builder(
                    controller: _logScrollController,
                    itemCount: controller.logs.length,
                    itemBuilder: (ctx, i) {
                      return Text(
                        '>> ${controller.logs[i]}',
                        style: const TextStyle(
                          fontFamily: 'Consolas',
                          fontSize: 11,
                        ),
                      );
                    },
                  ),
          ),
        ),
      ],
    );
  }
}

class FluentSettingsView extends StatelessWidget {
  const FluentSettingsView({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();
    final comboValue =
        controller.devices.contains(controller.selectedDevice)
            ? controller.selectedDevice
            : null;

    return ScaffoldPage.scrollable(
      header: const PageHeader(title: Text('Audio Settings')),
      children: [
        // Output device.
        Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(FluentIcons.speakers),
                  const SizedBox(width: 8),
                  const Expanded(child: Text('Output Device')),
                  IconButton(
                    icon: const Icon(FluentIcons.refresh, size: 14),
                    onPressed: controller.refreshDevices,
                  ),
                ],
              ),
              const SizedBox(height: 12),
              ComboBox<String>(
                isExpanded: true,
                placeholder: const Text('Select a speaker...'),
                value: comboValue,
                items: controller.devices.map((device) {
                  return ComboBoxItem<String>(
                    value: device,
                    child: Text(
                      device,
                      overflow: TextOverflow.ellipsis,
                    ),
                  );
                }).toList(),
                onChanged: controller.selectDevice,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),

        // Appearance.
        Expander(
          leading: const Icon(FluentIcons.brightness),
          header: const Text('Dark Mode'),
          trailing: ToggleSwitch(
            checked: controller.isDarkMode,
            onChanged: (v) => controller.toggleTheme(v),
          ),
          content: const Text('Switch between light and dark UI.'),
        ),
        const SizedBox(height: 12),

        // AI noise cancellation.
        Expander(
          leading: const Icon(FluentIcons.robot),
          header: const Text('AI Noise Cancellation'),
          trailing: ToggleSwitch(
            checked: controller.isAiEnabled,
            onChanged: (v) => controller.toggleAi(v),
          ),
          content: const Text('Reduces background noise using RNNoise.'),
        ),
        const SizedBox(height: 12),

        // Digital gain.
        Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(FluentIcons.volume3),
                  const SizedBox(width: 8),
                  Text(
                    'Digital Gain (Boost): ${controller.gainValue.toStringAsFixed(1)}x',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Slider(
                value: controller.gainValue,
                min: 1.0,
                max: 5.0,
                divisions: 40,
                label: '${controller.gainValue.toStringAsFixed(1)}x',
                onChanged: (v) => controller.setGain(v),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
