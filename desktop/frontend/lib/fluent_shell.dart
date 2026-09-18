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
  PaneDisplayMode _displayMode = PaneDisplayMode.expanded;

  void _togglePane() {
    setState(() {
      _displayMode = _displayMode == PaneDisplayMode.expanded
          ? PaneDisplayMode.compact
          : PaneDisplayMode.expanded;
    });
  }

  @override
  Widget build(BuildContext context) {
    return NavigationView(
      pane: NavigationPane(
        selected: _selectedIndex,
        onChanged: (index) => setState(() => _selectedIndex = index),
        // Expanded by default so Router/Settings labels stay visible in the
        // 800x600 window (mirroring the Material rail). The hamburger button
        // needs an explicit handler: with a fixed display mode the default
        // toggle only flips the compact overlay, which looks dead — so we
        // collapse/expand the pane ourselves.
        displayMode: _displayMode,
        toggleButton: PaneToggleButton(onPressed: _togglePane),
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

        // Microphone level — scrolling bar visualizer driven by the same
        // volumeNotifier (~10 Hz) so only this subtree repaints.
        Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(FluentIcons.microphone),
                  SizedBox(width: 8),
                  Text('Microphone Level'),
                ],
              ),
              const SizedBox(height: 12),
              _MicVisualizer(volumeNotifier: controller.volumeNotifier),
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
                  Icon(FluentIcons.play_solid),
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
                  Icon(FluentIcons.stop_solid),
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

/// Scrolling bar visualizer for the microphone level.
///
/// Keeps a rolling history of recent [volumeNotifier] samples and renders
/// them as bottom-anchored bars, newest on the right — so it reads as a live
/// audio visualizer instead of a progress fill. Only this widget rebuilds on
/// meter updates (~10 Hz).
class _MicVisualizer extends StatefulWidget {
  const _MicVisualizer({required this.volumeNotifier});

  final ValueNotifier<double> volumeNotifier;

  @override
  State<_MicVisualizer> createState() => _MicVisualizerState();
}

class _MicVisualizerState extends State<_MicVisualizer> {
  static const int _barCount = 48;
  late List<double> _history = List.filled(_barCount, 0.0);

  @override
  void initState() {
    super.initState();
    widget.volumeNotifier.addListener(_onVolume);
  }

  @override
  void dispose() {
    widget.volumeNotifier.removeListener(_onVolume);
    super.dispose();
  }

  void _onVolume() {
    setState(() {
      _history = [
        ..._history.skip(1),
        widget.volumeNotifier.value.clamp(0.0, 1.0),
      ];
    });
  }

  @override
  Widget build(BuildContext context) {
    final accent = FluentTheme.of(context).accentColor;
    return SizedBox(
      height: 110,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          for (final level in _history)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 1.5),
                child: FractionallySizedBox(
                  alignment: Alignment.bottomCenter,
                  heightFactor: level * 0.94 + 0.06,
                  child: Container(
                    decoration: BoxDecoration(
                      color: accent.withValues(alpha: 0.35 + 0.65 * level),
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class FluentSettingsView extends StatelessWidget {  const FluentSettingsView({super.key});

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
                    icon: const Icon(FluentIcons.refresh),
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
          leading: const Icon(FluentIcons.sunny),
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
