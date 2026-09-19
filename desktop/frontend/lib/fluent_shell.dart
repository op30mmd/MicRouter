import 'dart:async';
import 'dart:math' as math;

import 'package:fluent_ui/fluent_ui.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'backend_controller.dart';

/// Fluent shell — Windows only.
///
/// Uses the Fluent Design System ([FluentApp], [NavigationView],
/// [ScaffoldPage], [Card], [InfoBar], [RadioButton], [ToggleSwitch], ...) so the
/// Windows app feels native. Linux keeps using the Material/Yaru shell in
/// `material_shell.dart`; `main.dart` picks this shell when
/// `Platform.isWindows` is true.
///
/// Layout follows the WinUI 3 guidelines: page headers use the `title`
/// type ramp, settings are grouped into labelled `SettingsCard`-style rows,
/// button labels are sentence case, and spacing uses the 4px grid.

const String _kAppTitle = 'MicRouter PC';

/// Height of the custom title strip (WinUI title-bar height), giving the
/// hamburger breathing room below the native caption bar.
///
/// The library hardcodes a 38px slot above the pane only in its overlay
/// layout, used when the window is narrower than 2.5 × [_kOpenPaneWidth]
/// (550px); the 640px window minimum in `main.dart` keeps us out of it.
const double _kTitleBarHeight = 48;

/// Width of the expanded navigation pane. The library default (320px) eats
/// 40% of the 800px window; two short labels only need this much.
const double _kOpenPaneWidth = 220;

/// Spacing between cards inside one settings group (WinUI: 4px grid, 2 units).
const double _kCardGap = 8;

/// Spacing between sections on a page (WinUI: 6 grid units).
const double _kSectionGap = 24;

class FluentMicRouterApp extends StatefulWidget {
  const FluentMicRouterApp({super.key});

  @override
  State<FluentMicRouterApp> createState() => _FluentMicRouterAppState();
}

/// Bridge to the runner's `microuter/window` channel (see
/// `windows/runner/flutter_window.cpp`), which drives the DWM features no
/// plugin exposes fully: the Mica system backdrop and the caption theme.
class _WindowBackdrop {
  static const MethodChannel _channel = MethodChannel('microuter/window');

  /// Requests the Mica backdrop and a caption bar matching [dark].
  ///
  /// Returns whether Mica is active afterwards — `false` on Windows 10,
  /// pre-22H2 Windows 11, and in widget tests (no runner).
  static Future<bool> apply({required bool mica, required bool dark}) async {
    try {
      final active = await _channel.invokeMethod<bool>('setBackdrop', {
        'mica': mica,
        'dark': dark,
      });
      return active ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}

class _FluentMicRouterAppState extends State<FluentMicRouterApp> {
  // Theme objects are built only when their inputs change: FluentThemeData
  // has no value equality, so handing FluentApp a fresh instance on every
  // rebuild would invalidate the theme for the whole widget tree each time.
  late FluentThemeData _lightTheme = _buildTheme(Brightness.light);
  late FluentThemeData _darkTheme = _buildTheme(Brightness.dark);

  /// Whether DWM is painting Mica behind the window. While true the pane
  /// background is transparent so the backdrop shows through; the content
  /// area keeps its translucent layer fill on top, as in WinUI.
  bool _micaActive = false;

  bool? _appliedDarkMode;

  FluentThemeData _buildTheme(Brightness brightness) {
    return FluentThemeData(
      brightness: brightness,
      accentColor: Colors.blue,
      navigationPaneTheme: _micaActive
          ? const NavigationPaneThemeData(backgroundColor: Colors.transparent)
          : null,
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Fires only for the `isDarkMode` subscription made in [build].
    _syncWindowChrome(context.read<BackendController>().isDarkMode);
  }

  /// Subscribes to `isDarkMode` only. The controller notifies on every log
  /// line, status change and slider tick; none of those may rebuild the app
  /// root (see [_lightTheme]).
  bool _watchDarkMode(BuildContext context) {
    return context.select<BackendController, bool>((c) => c.isDarkMode);
  }

  /// Keeps the native window in step with the in-app theme: Mica backdrop
  /// plus a caption bar that follows the app's light/dark choice rather than
  /// the system's (the runner alone only follows the system setting).
  void _syncWindowChrome(bool isDarkMode) {
    if (_appliedDarkMode == isDarkMode) return;
    _appliedDarkMode = isDarkMode;
    unawaited(
      _WindowBackdrop.apply(mica: true, dark: isDarkMode).then((active) {
        if (!mounted || active == _micaActive) return;
        setState(() {
          _micaActive = active;
          _lightTheme = _buildTheme(Brightness.light);
          _darkTheme = _buildTheme(Brightness.dark);
        });
      }),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDarkMode = _watchDarkMode(context);

    return FluentApp(
      title: _kAppTitle,
      debugShowCheckedModeBanner: false,
      color: Colors.blue,
      themeMode: isDarkMode ? ThemeMode.dark : ThemeMode.light,
      theme: _lightTheme,
      darkTheme: _darkTheme,
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
  static const int _routerIndex = 0;
  static const int _settingsIndex = 1;

  int _selectedIndex = _routerIndex;
  PaneDisplayMode _displayMode = PaneDisplayMode.expanded;

  void _togglePane() {
    setState(() {
      _displayMode = _displayMode == PaneDisplayMode.expanded
          ? PaneDisplayMode.compact
          : PaneDisplayMode.expanded;
    });
  }

  void _select(int index) {
    if (index == _selectedIndex) return;
    setState(() => _selectedIndex = index);
  }

  @override
  Widget build(BuildContext context) {
    return NavigationView(
      // Real title strip, identical in both modes: the hamburger lives here
      // instead of the pane, so it can never shift or go dead on toggle.
      // (The library centers the pane toggle in a fixed header row when
      // expanded but top-aligns it when compact — no API override.)
      //
      // The hamburger occupies the back-button slot, which adds no padding of
      // its own; the `icon` slot hard-pads 16px after itself. The library's
      // PaneToggleButton is sized so its glyph sits exactly over the pane
      // icons below — add no inset. No text title: the native caption bar
      // already shows the app name right above.
      titleBar: TitleBar(
        height: _kTitleBarHeight,
        backButton: PaneToggleButton(onPressed: _togglePane),
      ),
      pane: NavigationPane(
        selected: _selectedIndex,
        onChanged: _select,
        // Expanded by default so Router/Settings labels stay visible in the
        // 800x600 window, mirroring the Material rail.
        displayMode: _displayMode,
        size: const NavigationPaneSize(openWidth: _kOpenPaneWidth),
        // No pane toggle button: the strip above owns the only hamburger.
        toggleButton: null,
        items: [
          PaneItem(
            icon: const Icon(FluentIcons.microphone),
            title: const Text('Router'),
            body: FluentRouterView(
              onOpenSettings: () => _select(_settingsIndex),
            ),
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
            body: const FluentAboutView(),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Router (dashboard) page
// ---------------------------------------------------------------------------

/// How the raw backend status string is surfaced in the status [InfoBar].
class _StatusPresentation {
  const _StatusPresentation(this.label, this.severity, {this.busy = false});

  final String label;
  final InfoBarSeverity severity;

  /// True while the backend is working towards a state (spinner shown).
  final bool busy;

  /// Maps the backend's status vocabulary (see `backend.cc` / `backend.py`
  /// and [BackendController]) to user-facing copy and an InfoBar severity.
  /// Unknown strings fall through unchanged so new backend states still
  /// display something sensible.
  factory _StatusPresentation.of(String status) {
    switch (status) {
      case 'running':
        return const _StatusPresentation(
          'Routing audio',
          InfoBarSeverity.success,
        );
      case 'connecting':
        return const _StatusPresentation(
          'Connecting to phone…',
          InfoBarSeverity.info,
          busy: true,
        );
      case 'stopped':
        return const _StatusPresentation('Stopped', InfoBarSeverity.info);
      case 'failed':
        return const _StatusPresentation(
          'Failed to start — check the logs',
          InfoBarSeverity.error,
        );
      case 'Connected to Engine':
        return const _StatusPresentation('Ready', InfoBarSeverity.info);
      case 'Initializing...':
        return const _StatusPresentation(
          'Starting engine…',
          InfoBarSeverity.info,
          busy: true,
        );
      case 'Waiting for Backend...':
        return const _StatusPresentation(
          'Waiting for the audio engine…',
          InfoBarSeverity.warning,
          busy: true,
        );
      case 'Backend Disconnected':
        return const _StatusPresentation(
          'Audio engine disconnected — reconnecting…',
          InfoBarSeverity.error,
          busy: true,
        );
      case 'Connection Error':
        return const _StatusPresentation(
          'Audio engine connection error — reconnecting…',
          InfoBarSeverity.error,
          busy: true,
        );
      default:
        return _StatusPresentation(status, InfoBarSeverity.info);
    }
  }
}

class FluentRouterView extends StatefulWidget {
  const FluentRouterView({super.key, required this.onOpenSettings});

  /// Invoked by the "no output device" call-to-action.
  final VoidCallback onOpenSettings;

  @override
  State<FluentRouterView> createState() => _FluentRouterViewState();
}

class _FluentRouterViewState extends State<FluentRouterView> {
  final ScrollController _logScrollController = ScrollController();

  /// Log length at the last build, so the list only auto-scrolls when new
  /// lines arrive — not on every unrelated controller notification, which
  /// would yank the view back down while the user reads older entries.
  int _seenLogCount = 0;

  @override
  void dispose() {
    _logScrollController.dispose();
    super.dispose();
  }

  void _scrollLogsToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_logScrollController.hasClients) return;
      _logScrollController.jumpTo(
        _logScrollController.position.maxScrollExtent,
      );
    });
  }

  Future<void> _copyLogs(List<String> logs) {
    return Clipboard.setData(ClipboardData(text: logs.join('\n')));
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();
    final theme = FluentTheme.of(context);
    final status = _StatusPresentation.of(controller.status);
    final isRunning = controller.status == 'running';
    // "connecting" counts as active: the backend is already in its retry
    // loop and honours "stop", but a second "start" would be ignored.
    final isActive = isRunning || controller.status == 'connecting';
    final hasDevice = controller.selectedDevice != null;
    final canStart = !isActive && hasDevice;

    if (controller.logs.length != _seenLogCount) {
      _seenLogCount = controller.logs.length;
      _scrollLogsToEnd();
    }

    return ScaffoldPage.scrollable(
      header: const PageHeader(title: Text('Dashboard')),
      children: [
        // Status — Fluent InfoBar mirrors the Material status pill.
        InfoBar(
          title: const Text('Status'),
          content: Text(status.label),
          severity: status.severity,
          action: status.busy
              ? const SizedBox.square(
                  dimension: 16,
                  child: ProgressRing(strokeWidth: 2.5),
                )
              : null,
        ),
        const SizedBox(height: _kSectionGap),

        // Microphone level — pulsing bar meter driven by volumeNotifier so
        // only the meter repaints on each sample.
        Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const _CardTitle(
                icon: FluentIcons.microphone,
                title: 'Microphone level',
              ),
              const SizedBox(height: 12),
              _MicVisualizer(volumeNotifier: controller.volumeNotifier),
            ],
          ),
        ),
        const SizedBox(height: _kSectionGap),

        // Transport controls.
        Row(
          children: [
            FilledButton(
              onPressed: canStart ? controller.startStreaming : null,
              child: const _ButtonLabel(
                icon: FluentIcons.play_solid,
                label: 'Start routing',
              ),
            ),
            const SizedBox(width: 8),
            Button(
              onPressed: isActive ? controller.stopStreaming : null,
              child: const _ButtonLabel(
                icon: FluentIcons.stop_solid,
                label: 'Stop',
              ),
            ),
          ],
        ),
        if (!hasDevice) ...[
          const SizedBox(height: 12),
          InfoBar(
            title: const Text('No output device selected'),
            content: const Text('Pick a speaker to enable routing.'),
            severity: InfoBarSeverity.warning,
            action: HyperlinkButton(
              onPressed: widget.onOpenSettings,
              child: const Text('Open settings'),
            ),
          ),
        ],
        const SizedBox(height: _kSectionGap),

        // System logs — Fluent Expander keeps parity with the Material log box.
        Expander(
          initiallyExpanded: true,
          leading: const Icon(FluentIcons.command_prompt),
          header: const Text('System logs'),
          trailing: Tooltip(
            message: 'Copy logs',
            child: IconButton(
              icon: const Icon(FluentIcons.copy),
              onPressed: controller.logs.isEmpty
                  ? null
                  : () => _copyLogs(controller.logs),
            ),
          ),
          contentPadding: const EdgeInsetsDirectional.all(12),
          content: SizedBox(
            height: 160,
            child: controller.logs.isEmpty
                ? Text(
                    'No logs yet.',
                    style: theme.typography.caption?.copyWith(
                      color: theme.resources.textFillColorSecondary,
                    ),
                  )
                : ListView.builder(
                    controller: _logScrollController,
                    itemCount: controller.logs.length,
                    itemBuilder: (context, index) {
                      return Text(
                        controller.logs[index],
                        style: theme.typography.caption?.copyWith(
                          fontFamily: 'Cascadia Mono',
                          fontFamilyFallback: const ['Consolas', 'monospace'],
                          height: 1.4,
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

/// Icon + text label for a transport button.
class _ButtonLabel extends StatelessWidget {
  const _ButtonLabel({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14),
        const SizedBox(width: 8),
        Text(label),
      ],
    );
  }
}

/// Section title inside a [Card]: 16px icon, `bodyStrong` text.
class _CardTitle extends StatelessWidget {
  const _CardTitle({required this.icon, required this.title});

  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) {
    final theme = FluentTheme.of(context);
    return Row(
      children: [
        Icon(icon, size: 16),
        const SizedBox(width: 8),
        Text(title, style: theme.typography.bodyStrong),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Microphone level meter
// ---------------------------------------------------------------------------

/// Perceptual shaping for raw meter samples.
///
/// The backend sends linear RMS (`min(rms / 2000, 1.0)`), which maps poorly
/// to a meter: quiet speech sits near zero while loud speech pins at 1.0.
/// The power curve lifts quiet/mid levels into visibility while keeping
/// silence at 0 and full scale at 1.
double shapeMeterLevel(double sample) {
  if (!sample.isFinite) return 0.0;
  return math.pow(sample.clamp(0.0, 1.0), 0.65).toDouble();
}

/// In-place pulsing bar visualizer for the microphone level.
///
/// The backend publishes a level ~30 times a second; a [Ticker] animates the
/// bars toward that target every frame with fast-attack / slow-release
/// ballistics, so motion is smooth regardless of the sample rate and the
/// bars always settle to zero after the last sample (the ticker stops itself
/// once everything is at rest, so an idle meter costs nothing). Painting is
/// a single [CustomPaint] behind a [RepaintBoundary]: samples repaint the
/// canvas without rebuilding any widgets.
class _MicVisualizer extends StatefulWidget {
  const _MicVisualizer({required this.volumeNotifier});

  final ValueNotifier<double> volumeNotifier;

  @override
  State<_MicVisualizer> createState() => _MicVisualizerState();
}

class _MicVisualizerState extends State<_MicVisualizer>
    with SingleTickerProviderStateMixin {
  static const int _barCount = 32;

  /// Bars are treated as settled once within this distance of their target.
  static const double _restEpsilon = 0.002;

  /// Time constants (per second): how quickly a bar closes the gap to its
  /// target when rising vs. falling. Higher is faster.
  static const double _attackRate = 28.0;
  static const double _releaseRate = 9.0;

  /// Fixed per-bar weights: deliberately irregular (seeded once) so the
  /// meter reads as a live audio spectrum instead of a smooth hill.
  static final List<double> _weights = () {
    final rand = math.Random(1234);
    return List.generate(_barCount, (_) => 0.25 + 0.75 * rand.nextDouble());
  }();

  /// Per-bar ballistics multipliers (independent seeds) so bars don't move
  /// in lockstep.
  static final List<double> _attackScale = () {
    final rand = math.Random(987);
    return List.generate(_barCount, (_) => 0.7 + 0.6 * rand.nextDouble());
  }();
  static final List<double> _releaseScale = () {
    final rand = math.Random(555);
    return List.generate(_barCount, (_) => 0.6 + 0.8 * rand.nextDouble());
  }();

  final List<double> _levels = List.filled(_barCount, 0.0);
  final _MeterRepaint _repaint = _MeterRepaint();
  late final Ticker _ticker;

  /// Smoothed raw sample: tames backend jitter before shaping.
  double _smooth = 0.0;

  /// Shaped level the bars are currently easing toward.
  double _target = 0.0;

  Duration _lastTick = Duration.zero;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick);
    widget.volumeNotifier.addListener(_onVolume);
  }

  @override
  void didUpdateWidget(_MicVisualizer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.volumeNotifier != widget.volumeNotifier) {
      oldWidget.volumeNotifier.removeListener(_onVolume);
      widget.volumeNotifier.addListener(_onVolume);
    }
  }

  @override
  void dispose() {
    widget.volumeNotifier.removeListener(_onVolume);
    _ticker.dispose();
    _repaint.dispose();
    super.dispose();
  }

  void _onVolume() {
    var raw = widget.volumeNotifier.value;
    if (!raw.isFinite) raw = 0.0;
    _smooth += (raw.clamp(0.0, 1.0) - _smooth) * 0.5;
    _target = shapeMeterLevel(_smooth);
    if (!_ticker.isActive) {
      _lastTick = Duration.zero;
      _ticker.start();
    }
  }

  void _onTick(Duration elapsed) {
    // Clamp so a long frame (window drag, breakpoint) doesn't snap the bars.
    final dt = ((elapsed - _lastTick).inMicroseconds / 1e6).clamp(0.0, 0.1);
    _lastTick = elapsed;

    var settled = true;
    for (var i = 0; i < _barCount; i++) {
      final target = _target * _weights[i];
      final level = _levels[i];
      final rate = target > level
          ? _attackRate * _attackScale[i]
          : _releaseRate * _releaseScale[i];
      // Exponential approach: frame-rate independent easing.
      final next = level + (target - level) * (1 - math.exp(-rate * dt));
      _levels[i] = next;
      if ((target - next).abs() > _restEpsilon) settled = false;
    }

    if (settled) {
      // Snap to the exact targets so the meter reads a clean zero at rest.
      for (var i = 0; i < _barCount; i++) {
        _levels[i] = _target * _weights[i];
      }
      _ticker.stop();
    }
    _repaint.pulse();
  }

  @override
  Widget build(BuildContext context) {
    final accent = FluentTheme.of(context).accentColor;
    return Semantics(
      label: 'Microphone level meter',
      child: ExcludeSemantics(
        child: SizedBox(
          height: 110,
          width: double.infinity,
          child: RepaintBoundary(
            child: CustomPaint(
              painter: _MeterPainter(
                levels: _levels,
                color: accent,
                repaint: _repaint,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Repaint trigger for [_MeterPainter]; exposes [notifyListeners] publicly.
class _MeterRepaint extends ChangeNotifier {
  void pulse() => notifyListeners();
}

class _MeterPainter extends CustomPainter {
  _MeterPainter({
    required this.levels,
    required this.color,
    required Listenable repaint,
  }) : super(repaint: repaint);

  /// Live bar heights in `0..1`, shared by reference with the state.
  final List<double> levels;
  final Color color;

  static const double _gap = 3;
  static const double _minHeight = 4;
  static const Radius _radius = Radius.circular(2);

  @override
  void paint(Canvas canvas, Size size) {
    final count = levels.length;
    if (count == 0 || size.isEmpty) return;

    final barWidth = (size.width - _gap * (count - 1)) / count;
    if (barWidth <= 0) return;

    final track = Paint()..color = color.withValues(alpha: 0.08);
    final fill = Paint();

    for (var i = 0; i < count; i++) {
      final left = i * (barWidth + _gap);
      final level = levels[i].clamp(0.0, 1.0);
      final height = math.max(_minHeight, level * size.height);

      // Faint full-height track so the meter's extent reads even at rest.
      canvas.drawRRect(
        RRect.fromLTRBR(left, 0, left + barWidth, size.height, _radius),
        track,
      );
      fill.color = color.withValues(alpha: 0.35 + 0.65 * level);
      canvas.drawRRect(
        RRect.fromLTRBR(
          left,
          size.height - height,
          left + barWidth,
          size.height,
          _radius,
        ),
        fill,
      );
    }
  }

  @override
  bool shouldRepaint(_MeterPainter oldDelegate) {
    return oldDelegate.color != color || oldDelegate.levels != levels;
  }
}

// ---------------------------------------------------------------------------
// Settings page
// ---------------------------------------------------------------------------

class FluentSettingsView extends StatelessWidget {
  const FluentSettingsView({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();
    final theme = FluentTheme.of(context);
    final selectedDevice = controller.devices.contains(controller.selectedDevice)
        ? controller.selectedDevice
        : null;

    return ScaffoldPage.scrollable(
      header: const PageHeader(title: Text('Audio Settings')),
      children: [
        const _SettingsGroupHeader('Output'),
        _SettingsCard(
          icon: FluentIcons.speakers,
          title: 'Output device',
          description: 'Speaker or headset that plays the phone microphone.',
          trailing: Tooltip(
            message: 'Refresh devices',
            child: IconButton(
              icon: const Icon(FluentIcons.refresh),
              onPressed: controller.refreshDevices,
            ),
          ),
          // Inline radio list rather than a ComboBox, mirroring Windows 11's
          // Sound settings ("Choose where to play sound"). The list is short
          // (one row per real endpoint since the backend dedups host APIs),
          // and it avoids fluent_ui's popup route, whose open/close
          // animation costs 20–50ms per frame on older GPUs (fade layer +
          // animated clip + raster-cached surface) with no knob to tune.
          child: controller.devices.isEmpty
              ? Text(
                  'No output devices found.',
                  style: theme.typography.caption?.copyWith(
                    color: theme.resources.textFillColorSecondary,
                  ),
                )
              : RadioGroup<String>(
                  groupValue: selectedDevice,
                  onChanged: controller.selectDevice,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (final device in controller.devices)
                        Padding(
                          padding: const EdgeInsetsDirectional.only(top: 8),
                          child: RadioButton<String>(
                            value: device,
                            content: Text(
                              device,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
        ),
        const SizedBox(height: _kSectionGap),

        const _SettingsGroupHeader('Processing'),
        _SettingsCard(
          icon: FluentIcons.robot,
          title: 'AI noise cancellation',
          description: 'Reduces background noise using RNNoise.',
          trailing: ToggleSwitch(
            checked: controller.isAiEnabled,
            onChanged: controller.toggleAi,
            content: Text(controller.isAiEnabled ? 'On' : 'Off'),
          ),
        ),
        const SizedBox(height: _kCardGap),
        _SettingsCard(
          icon: FluentIcons.volume3,
          title: 'Digital gain',
          description: 'Boosts the microphone signal before playback.',
          trailing: Text('${controller.gainValue.toStringAsFixed(1)}×'),
          child: Slider(
            value: controller.gainValue,
            min: 1.0,
            max: 5.0,
            divisions: 40,
            label: '${controller.gainValue.toStringAsFixed(1)}×',
            onChanged: controller.setGain,
          ),
        ),
        const SizedBox(height: _kSectionGap),

        const _SettingsGroupHeader('Appearance'),
        _SettingsCard(
          icon: FluentIcons.color,
          title: 'Dark mode',
          description: 'Use a dark theme for the app window.',
          trailing: ToggleSwitch(
            checked: controller.isDarkMode,
            onChanged: controller.toggleTheme,
            content: Text(controller.isDarkMode ? 'On' : 'Off'),
          ),
        ),
      ],
    );
  }
}

/// Small `bodyStrong` label above a group of settings cards (WinUI pattern).
class _SettingsGroupHeader extends StatelessWidget {
  const _SettingsGroupHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 4, bottom: 8),
      child: Text(text, style: FluentTheme.of(context).typography.bodyStrong),
    );
  }
}

/// A WinUI `SettingsCard`: leading icon, title + optional description, and
/// an action control at the end. When [child] is given it is rendered below
/// the header row (the `SettingsExpander`-style layout for wide controls
/// like a [Slider] or a [RadioGroup]).
class _SettingsCard extends StatelessWidget {
  const _SettingsCard({
    required this.icon,
    required this.title,
    this.description,
    this.trailing,
    this.child,
  });

  final IconData icon;
  final String title;
  final String? description;
  final Widget? trailing;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final theme = FluentTheme.of(context);
    return Card(
      padding: const EdgeInsetsDirectional.fromSTEB(16, 12, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(icon, size: 20),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: theme.typography.body),
                    if (description != null)
                      Padding(
                        padding: const EdgeInsetsDirectional.only(top: 2),
                        child: Text(
                          description!,
                          style: theme.typography.caption?.copyWith(
                            color: theme.resources.textFillColorSecondary,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              if (trailing != null)
                Padding(
                  padding: const EdgeInsetsDirectional.only(start: 16),
                  child: trailing,
                ),
            ],
          ),
          if (child != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: 12),
              child: child,
            ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// About page
// ---------------------------------------------------------------------------

class FluentAboutView extends StatelessWidget {
  const FluentAboutView({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = FluentTheme.of(context);
    return ScaffoldPage.scrollable(
      header: const PageHeader(title: Text('About')),
      children: [
        Card(
          padding: const EdgeInsetsDirectional.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_kAppTitle, style: theme.typography.subtitle),
              const SizedBox(height: 8),
              Text(
                'Route your Android microphone to Windows over USB with low '
                'latency.',
                style: theme.typography.body,
              ),
              const SizedBox(height: 12),
              Text(
                'Built with Flutter and Fluent UI.',
                style: theme.typography.caption?.copyWith(
                  color: theme.resources.textFillColorSecondary,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
