import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:path/path.dart' as p;
import 'package:window_manager/window_manager.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();

  WindowOptions windowOptions = const WindowOptions(
    size: Size(800, 600),
    center: true,
    backgroundColor: Colors.transparent,
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

class BackendController extends ChangeNotifier {
  Socket? _socket;
  Process? _pythonProcess;
  String status = "Initializing...";

  double currentVolume = 0.0;
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
    if (selectedDevice != null) {
      await prefs.setString('selectedDevice', selectedDevice!);
    }
  }

  void _startEmbeddedBackend() async {
    // Locate the backend relative to the executable
    String exePath = Platform.resolvedExecutable;
    String dir = File(exePath).parent.path;
    String backendExeName = Platform.isWindows ? 'microuter_backend.exe' : 'microuter_backend';
    String backendExePath = p.join(dir, 'backend', backendExeName);
    String scriptPath = p.join(dir, 'backend', 'backend.py');

    try {
      if (await File(backendExePath).exists()) {
        // Packaged release: self-contained backend, no system Python required.
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

    // Give it a moment to bind the port
    await Future.delayed(const Duration(seconds: 1));
    connectToPython();
  }

  @override
  void dispose() {
    _socket?.destroy();
    _pythonProcess?.kill();
    super.dispose();
  }

  void connectToPython() async {
    try {
      _socket = await Socket.connect('127.0.0.1', 5000);
      status = "Connected to Engine";
      notifyListeners();

      // Request initial state
      sendCommand("get_devices");

      _socket!.listen(
        _onDataReceived,
        onDone: () {
          status = "Backend Disconnected";
          _socket = null;
          notifyListeners();
          _reconnect();
        },
        onError: (e) {
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
    }
  }

  void _reconnect() {
    Future.delayed(const Duration(seconds: 2), connectToPython);
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
        currentVolume = (msg['value'] as num).toDouble();
        break;
      case 'log':
        _log(msg['message']);
        break;
      case 'error':
        _log("ERROR: ${msg['message']}");
        break;
      case 'devices':
        devices = List<String>.from(msg['payload']);
        // Auto-select first device if none selected
        if (devices.isNotEmpty && selectedDevice == null) {
          selectedDevice = devices.first;
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
}

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
    final controller = Provider.of<BackendController>(context, listen: false);
    await controller.saveSettings();
    await windowManager.destroy();
  }

  ThemeData _buildTheme(bool isDark) {
    final isLinux = Platform.isLinux;
    final ubuntuOrange = const Color(0xFFE95420);

    if (isLinux) {
      if (isDark) {
        return ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          primaryColor: ubuntuOrange,
          scaffoldBackgroundColor: const Color(0xFF300A24), // Ubuntu Dark Aubergine
          colorScheme: ColorScheme.dark(
            primary: ubuntuOrange,
            secondary: ubuntuOrange,
            surface: const Color(0xFF3D3D3D),
            onSurface: Colors.white,
            surfaceContainerHighest: const Color(0xFF4D4D4D),
          ),
          cardTheme: CardThemeData(
            color: const Color(0xFF3D3D3D),
            elevation: 0,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          ),
        );
      } else {
        return ThemeData(
          useMaterial3: true,
          brightness: Brightness.light,
          primaryColor: ubuntuOrange,
          scaffoldBackgroundColor: const Color(0xFFF7F7F7),
          colorScheme: ColorScheme.light(
            primary: ubuntuOrange,
            secondary: ubuntuOrange,
            surface: Colors.white,
            onSurface: const Color(0xFF333333),
            surfaceContainerHighest: const Color(0xFFEEEEEE),
          ),
          cardTheme: CardThemeData(
            color: Colors.white,
            elevation: 1,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          ),
        );
      }
    } else {
      // Default Modern Cyan/Purple Theme
      if (isDark) {
        return ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          scaffoldBackgroundColor: const Color(0xFF121212),
          colorScheme: ColorScheme.dark(
            primary: Colors.cyanAccent,
            secondary: Colors.purpleAccent,
            surface: const Color(0xFF1E1E1E),
            surfaceContainerHighest: const Color(0xFF2C2C2C),
          ),
        );
      } else {
        return ThemeData(
          useMaterial3: true,
          brightness: Brightness.light,
          scaffoldBackgroundColor: const Color(0xFFF5F5F5),
          colorScheme: ColorScheme.light(
            primary: Colors.cyan,
            secondary: Colors.purple,
            surface: Colors.white,
            surfaceContainerHighest: const Color(0xFFE0E0E0),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    return MaterialApp(
      title: 'MicRouter PC',
      debugShowCheckedModeBanner: false,
      themeMode: controller.isDarkMode ? ThemeMode.dark : ThemeMode.light,
      theme: _buildTheme(false),
      darkTheme: _buildTheme(true),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _selectedIndex = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: _selectedIndex,
            backgroundColor: Theme.of(context).colorScheme.surface.withOpacity(0.5),
            labelType: NavigationRailLabelType.all,
            onDestinationSelected: (int index) {
              setState(() {
                _selectedIndex = index;
              });
            },
            destinations: const [
              NavigationRailDestination(
                icon: Icon(Icons.mic_none_outlined),
                selectedIcon: Icon(Icons.mic),
                label: Text('Router'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.settings_outlined),
                selectedIcon: Icon(Icons.settings),
                label: Text('Settings'),
              ),
            ],
          ),
          VerticalDivider(thickness: 1, width: 1, color: Theme.of(context).dividerColor.withOpacity(0.1)),

          Expanded(
            child: _selectedIndex == 0
                ? const RouterView()
                : const SettingsView(),
          ),
        ],
      ),
    );
  }
}

class RouterView extends StatefulWidget {
  const RouterView({super.key});

  @override
  State<RouterView> createState() => _RouterViewState();
}

class _RouterViewState extends State<RouterView> {
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    // Auto-scroll logs
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
      }
    });

    return Padding(
      padding: const EdgeInsets.all(40.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text("Dashboard", style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 40),

          Center(
            child: Container(
              height: 80,
              width: double.infinity,
              constraints: const BoxConstraints(maxWidth: 700),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.5),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: Theme.of(context).dividerColor.withOpacity(0.1)),
                boxShadow: [
                  BoxShadow(color: Colors.black.withOpacity(0.1), blurRadius: 10, offset: const Offset(0, 4))
                ]
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(20),
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: controller.currentVolume.clamp(0.0, 1.0),
                  child: Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(colors: [
                        Theme.of(context).colorScheme.primary,
                        Theme.of(context).colorScheme.secondary,
                      ]),
                    ),
                  ),
                ),
              ),
            ),
          ),

          const SizedBox(height: 15),

          // Status Text
          Center(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: controller.status.contains("running")
                    ? Colors.green.withOpacity(0.2)
                    : Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(20)
              ),
              child: Text(
                "STATUS: ${controller.status.toUpperCase()}",
                style: TextStyle(
                  letterSpacing: 1.2,
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: controller.status.contains("running")
                      ? (Theme.of(context).brightness == Brightness.dark ? Colors.greenAccent : Colors.green[700])
                      : Theme.of(context).hintColor
                )
              ),
            ),
          ),

          const SizedBox(height: 60),

          Center(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: (controller.status == "running" || controller.selectedDevice == null)
                      ? null
                      : controller.startStreaming,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text("START ROUTING"),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 22),
                    textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))
                  ),
                ),
                const SizedBox(width: 20),
                OutlinedButton.icon(
                  onPressed: controller.status == "running" ? controller.stopStreaming : null,
                  icon: const Icon(Icons.stop),
                  label: const Text("STOP"),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 22),
                    foregroundColor: Colors.redAccent,
                    side: const BorderSide(color: Colors.redAccent),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))
                  ),
                ),
              ],
            ),
          ),

          const Spacer(),
          Divider(color: Theme.of(context).dividerColor.withOpacity(0.1)),
          const SizedBox(height: 10),

          Text("System Logs:", style: TextStyle(color: Theme.of(context).hintColor, fontSize: 12)),
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(8),
            height: 120,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest.withOpacity(0.3),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Theme.of(context).dividerColor.withOpacity(0.1))
            ),
            child: ListView.builder(
              controller: _scrollController,
              itemCount: controller.logs.length,
              itemBuilder: (ctx, i) {
                return Text(
                  ">> ${controller.logs[i]}",
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurface.withOpacity(0.7)
                  ),
                );
              },
            ),
          )
        ],
      ),
    );
  }
}

class SettingsView extends StatelessWidget {
  const SettingsView({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    return Padding(
      padding: const EdgeInsets.all(40.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text("Audio Settings", style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 40),

          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text("Output Device", style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
                      IconButton(
                        icon: const Icon(Icons.refresh, size: 20),
                        onPressed: controller.refreshDevices,
                        tooltip: "Refresh Devices",
                      )
                    ],
                  ),
                  const SizedBox(height: 10),
                  DropdownButtonFormField<String>(
                    value: controller.selectedDevice,
                    decoration: InputDecoration(
                      border: const OutlineInputBorder(),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                      filled: true,
                      fillColor: Theme.of(context).colorScheme.surface
                    ),
                    dropdownColor: Theme.of(context).colorScheme.surface,
                    isExpanded: true,
                    hint: const Text("Select a speaker..."),
                    items: controller.devices.map((String value) {
                      return DropdownMenuItem<String>(
                        value: value,
                        child: Text(value, overflow: TextOverflow.ellipsis),
                      );
                    }).toList(),
                    onChanged: controller.selectDevice,
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 20),

          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Row(
                    children: [
                      Icon(Icons.dark_mode, color: Theme.of(context).colorScheme.primary),
                      const SizedBox(width: 15),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("Dark Mode", style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
                            Text("Switch between light and dark UI", style: TextStyle(fontSize: 12, color: Colors.grey)),
                          ],
                        ),
                      ),
                      Switch(
                        value: controller.isDarkMode,
                        onChanged: (val) => controller.toggleTheme(val),
                      ),
                    ],
                  ),

                  const Divider(height: 30, color: Colors.white10),
                  Row(
                    children: [
                      const Icon(Icons.auto_awesome, color: Colors.amber),
                      const SizedBox(width: 15),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("AI Noise Cancellation", style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
                            Text("Reduces background noise using RNNoise", style: TextStyle(fontSize: 12, color: Colors.grey)),
                          ],
                        ),
                      ),
                      Switch(
                        value: controller.isAiEnabled,
                        onChanged: (val) => controller.toggleAi(val),
                        activeColor: Colors.amber,
                      ),
                    ],
                  ),

                  const Divider(height: 30, color: Colors.white10),

                  Row(
                    children: [
                      const Icon(Icons.volume_up, color: Colors.cyanAccent),
                      const SizedBox(width: 15),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("Digital Gain (Boost): ${controller.gainValue.toStringAsFixed(1)}x", style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
                            Slider(
                              value: controller.gainValue,
                              min: 1.0,
                              max: 5.0,
                              divisions: 40,
                              activeColor: Colors.cyanAccent,
                              label: "${controller.gainValue.toStringAsFixed(1)}x",
                              onChanged: (val) => controller.setGain(val),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
