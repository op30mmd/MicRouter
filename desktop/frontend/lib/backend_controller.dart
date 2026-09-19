import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';

/// Shared business logic for both shells:
/// - [MaterialMicRouterApp] (Linux + fallback) in `material_shell.dart`
/// - [FluentMicRouterApp] (Windows only) in `fluent_shell.dart`
///
/// Toolkit-agnostic on purpose: only depends on `flutter/foundation.dart`,
/// never on `material.dart` or `fluent_ui`, so both UIs can share it without
/// import conflicts.
class BackendController extends ChangeNotifier {
  Socket? _socket;
  Process? _pythonProcess;
  String status = "Initializing...";
  // Once set, no new connects/reconnects are scheduled and socket callbacks
  // stay silent — so shutdown can never hang, loop forever, or notify after
  // dispose (any of which wedges app close).
  bool _shuttingDown = false;
  // True after the first successful handshake; retries are hot (250 ms)
  // until then so a still-starting backend is picked up in milliseconds,
  // and back off (2 s) afterwards to avoid hammering a dead backend.
  bool _everConnected = false;
  // Guards against overlapping connect attempts piling up.
  bool _connectInFlight = false;

  // Volume meter state lives in its own notifier so the ~10 Hz meter updates
  // repaint only the meter (via ValueListenableBuilder) instead of rebuilding
  // the whole app through notifyListeners() — that full rebuild is what made
  // the visualizer stutter and lag behind the audio.
  final ValueNotifier<double> volumeNotifier = ValueNotifier(0.0);
  double get currentVolume => volumeNotifier.value;
  double gainValue = 1.0;
  bool isAiEnabled = false;
  bool isDarkMode = true;
  List<String> logs = [];
  List<String> devices = [];
  String? selectedDevice;

  final List<int> _socketBytesBuffer = [];

  BackendController() {
    _startEmbeddedBackend();
    loadSettings();
  }

  Future<void> loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    isAiEnabled = prefs.getBool('isAiEnabled') ?? false;
    gainValue = prefs.getDouble('gainValue') ?? 1.0;
    isDarkMode = prefs.getBool('isDarkMode') ?? true;
    selectedDevice = prefs.getString('selectedDevice');
    // Show the last-known device list instantly instead of an empty
    // dropdown while the backend boots/scans; the live list replaces it
    // on connect (stale-while-revalidate).
    final cached = prefs.getStringList('devicesCache');
    if (cached != null && cached.isNotEmpty) {
      devices = dedupeDeviceNames(cached);
      selectedDevice ??= devices.first;
    }

    // Apply loaded settings
    if (isAiEnabled) toggleAi(isAiEnabled);
    if (gainValue != 1.0) setGain(gainValue);

    notifyListeners();
  }

  Future<void> saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('isAiEnabled', isAiEnabled);
    await prefs.setDouble('gainValue', gainValue);
    await prefs.setBool('isDarkMode', isDarkMode);
    await prefs.setStringList('devicesCache', devices);
    if (selectedDevice != null) {
      await prefs.setString('selectedDevice', selectedDevice!);
    }
  }

  void _startEmbeddedBackend() async {
    // Locate the backend relative to the executable
    String exePath = Platform.resolvedExecutable;
    String dir = File(exePath).parent.path;
    String backendExeName =
        Platform.isWindows ? 'microuter_backend.exe' : 'microuter_backend';
    String backendExePath = p.join(dir, 'backend', backendExeName);
    String cppExeName = Platform.isWindows
        ? 'microuter_backend_cpp.exe'
        : 'microuter_backend_cpp';
    String cppExePath = p.join(dir, 'backend', cppExeName);
    String scriptPath = p.join(dir, 'backend', 'backend.py');

    try {
      if (await File(cppExePath).exists()) {
        // Preferred: native C++ engine, no Python required.
        _log("Looking for bundled C++ backend at: $cppExePath");
        _pythonProcess = await Process.start(cppExePath, []);
        _log("Backend started using the native C++ executable.");

        _pythonProcess!.stdout.transform(utf8.decoder).listen((data) {
          if (data.trim().isNotEmpty) print("BACKEND: $data");
        });
        _pythonProcess!.stderr.transform(utf8.decoder).listen((data) {
          if (!data.contains("ALSA") && !data.contains("jack")) {
            // The C++ binary logs real errors to stderr (RNNoise, PortAudio...).
            _log(data.trim());
          }
        });
      } else if (await File(backendExePath).exists()) {
        // PyInstaller fallback: self-contained Python backend, no system Python.
        _log("Looking for bundled backend at: $backendExePath");
        _pythonProcess = await Process.start(backendExePath, []);
        _log("Backend started using bundled executable.");
        _pythonProcess!.stderr.transform(utf8.decoder).listen((data) {
          if (!data.contains("ALSA") && !data.contains("jack")) {
            print("PY_ERR: $data");
          }
        });
      } else if (await File(scriptPath).exists()) {
        // Dev mode: running from source, fall back to system Python.
        _log("Looking for script at: $scriptPath");
        String pythonCmd = Platform.isWindows ? 'python' : 'python3';
        _pythonProcess = await Process.start(pythonCmd, [scriptPath]);
        _log("Python backend started using $pythonCmd.");

        _pythonProcess!.stderr.transform(utf8.decoder).listen((data) {
          if (!data.contains("ALSA") && !data.contains("jack")) {
            print("PY_ERR: $data");
          }
        });
      } else {
        _log("Backend not found. Assuming external/dev backend.");
      }
    } catch (e) {
      _log("Failed to launch backend: $e");
    }

    // Probe the port immediately — no fixed sleep. A ready backend connects
    // in milliseconds; a still-starting one is picked up by hot retries.
    connectToPython();
  }

  @override
  void dispose() {
    unawaited(shutdown());
    volumeNotifier.dispose();
    super.dispose();
  }

  /// Terminates the connection and the spawned backend process so no orphan
  /// keeps holding port 5000 after the app goes away.
  Future<void> shutdown() async {
    _shuttingDown = true;
    try {
      _socket?.destroy();
    } catch (_) {}
    _socket = null;
    final proc = _pythonProcess;
    _pythonProcess = null;
    if (proc != null) {
      // Clean exit first (stdin EOF trips the backend's parent watchdog),
      // then the hammer in case it is stuck somewhere.
      try {
        await proc.stdin.close();
      } catch (_) {}
      try {
        proc.kill();
      } catch (_) {}
    }
  }

  void connectToPython() async {
    if (_shuttingDown || _connectInFlight) return;
    _connectInFlight = true;
    try {
      _socket = await Socket.connect('127.0.0.1', 5000)
          .timeout(const Duration(seconds: 1));
      // Small realtime frames (volume meter): don't let Nagle batch them.
      _socket!.setOption(SocketOption.tcpNoDelay, true);
      _everConnected = true;
      status = "Connected to Engine";
      notifyListeners();

      // Request initial state
      sendCommand("get_devices");

      _socket!.listen(
        _onDataReceived,
        onDone: () {
          if (_shuttingDown) return;
          status = "Backend Disconnected";
          _socket = null;
          notifyListeners();
          _reconnect();
        },
        onError: (e) {
          if (_shuttingDown) return;
          status = "Connection Error";
          _socket = null;
          notifyListeners();
          _reconnect();
        },
      );
    } catch (e) {
      status = "Waiting for Backend...";
      notifyListeners();
      _reconnect();
    } finally {
      _connectInFlight = false;
    }
  }

  void _reconnect() {
    if (_shuttingDown) return;
    final delay = _everConnected
        ? const Duration(seconds: 2)
        : const Duration(milliseconds: 250);
    Future.delayed(delay, connectToPython);
  }

  // --- CRITICAL FIX: Handle Fragmented TCP Packets (Byte-level) ---
  void _onDataReceived(List<int> data) {
    _socketBytesBuffer.addAll(data);

    while (true) {
      int index = _socketBytesBuffer.indexOf(10); // 10 is '\n'
      if (index == -1) break;

      List<int> lineBytes = _socketBytesBuffer.sublist(0, index);
      _socketBytesBuffer.removeRange(0, index + 1);

      if (lineBytes.isNotEmpty) {
        try {
          String line = utf8.decode(lineBytes).trim();
          if (line.isNotEmpty) {
            _handleMessage(jsonDecode(line));
          }
        } catch (e) {
          print("Socket Data Error: $e");
        }
      }
    }
  }

  void _handleMessage(Map<String, dynamic> msg) {
    switch (msg['type']) {
      case 'status':
        status = msg['payload'];
        break;
      case 'volume':
        volumeNotifier.value = (msg['value'] as num).toDouble();
        return;
      case 'log':
        _log(msg['message']);
        return;
      case 'error':
        _log("ERROR: ${msg['message']}");
        return;
      case 'devices':
        devices = dedupeDeviceNames(List<String>.from(msg['payload']));
        if (devices.isNotEmpty) {
          if (selectedDevice == null) {
            // Auto-select first device if none selected
            selectedDevice = devices.first;
          } else if (!devices.contains(selectedDevice)) {
            // Re-resolve a saved selection against the deduped list so a
            // whitespace/case variant doesn't orphan the saved choice.
            final want = selectedDevice!.trim().toLowerCase();
            String? match;
            for (final d in devices) {
              if (d.trim().toLowerCase() == want) {
                match = d;
                break;
              }
            }
            // Gone for good (unplugged, or a pseudo-device from before the
            // backend listed WASAPI endpoints only): fall back to the first
            // endpoint rather than leaving nothing selected.
            selectedDevice = match ?? devices.first;
          }
        }
        break;
    }
    notifyListeners();
  }

  void _log(String message) {
    logs.add(message);
    // Limit log size to prevent memory issues
    if (logs.length > 200) {
      logs.removeAt(0);
    }
    notifyListeners();
  }

  void sendCommand(String cmd, [Map<String, dynamic> args = const {}]) {
    if (_socket != null) {
      Map<String, dynamic> commandData = {'command': cmd}..addAll(args);
      _socket!.write(jsonEncode(commandData) + "\n");
    }
  }

  void startStreaming() {
    if (selectedDevice != null) {
      sendCommand('start', {'device_name': selectedDevice, 'port': 6000});
    } else {
      _log("ERROR: No output device selected!");
    }
  }

  void stopStreaming() {
    sendCommand('stop');
  }

  void selectDevice(String? deviceName) {
    selectedDevice = deviceName;
    notifyListeners();
  }

  void setGain(double val) {
    gainValue = val;
    sendCommand('set_gain', {'value': val});
    notifyListeners();
  }

  void toggleAi(bool value) {
    isAiEnabled = value;
    sendCommand("toggle_rnnoise", {"value": value});
    notifyListeners();
  }

  void toggleTheme(bool value) {
    isDarkMode = value;
    saveSettings();
    notifyListeners();
  }

  void refreshDevices() {
    sendCommand("get_devices");
  }

  /// Collapse duplicate output-device entries while preserving backend order.
  ///
  /// Both backends enumerate every host API, comparing exact names — so the
  /// same endpoint can arrive twice under near-identical names (trailing
  /// whitespace or case variants across WASAPI/MME, Pulse/ALSA). Comparison
  /// is on the normalized name; the first-seen original is kept for display.
  static List<String> dedupeDeviceNames(List<String> names) {
    final seen = <String>{};
    final result = <String>[];
    for (final name in names) {
      if (seen.add(name.trim().toLowerCase())) result.add(name);
    }
    return result;
  }
}
