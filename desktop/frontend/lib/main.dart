import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:path/path.dart' as p;

void main() {
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
  List<String> logs = [];
  List<String> devices = [];
  String? selectedDevice;

  String _socketBuffer = "";

  BackendController() {
    _startEmbeddedBackend();
  }

  void _startEmbeddedBackend() async {
    // Locate the backend script relative to the executable
    String exePath = Platform.resolvedExecutable;
    String dir = File(exePath).parent.path;
    String scriptPath = p.join(dir, 'backend', 'backend.py');

    _log("Looking for script at: $scriptPath");

    // Check if script exists (Release mode vs Debug mode adjustments might be needed)
    if (await File(scriptPath).exists()) {
      try {
        _pythonProcess = await Process.start('python', [scriptPath]);
        _log("Python backend started.");

        // Listen to Python's STDERR for debugging
        _pythonProcess!.stderr.transform(utf8.decoder).listen((data) {
             // Optional: Filter out noisy PyAudio logs
             if (!data.contains("ALSA") && !data.contains("jack")) {
                 print("PY_ERR: $data");
             }
        });
      } catch (e) {
        _log("Failed to launch python: $e");
      }
    } else {
      _log("backend.py not found. Assuming external/dev backend.");
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

      _socket!.cast<List<int>>().transform(utf8.decoder).listen(
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

  // --- CRITICAL FIX: Handle Fragmented TCP Packets ---
  void _onDataReceived(String data) {
    _socketBuffer += data;

    while (_socketBuffer.contains('\n')) {
      int index = _socketBuffer.indexOf('\n');
      String line = _socketBuffer.substring(0, index).trim();
      _socketBuffer = _socketBuffer.substring(index + 1);

      if (line.isNotEmpty) {
        try {
          _handleMessage(jsonDecode(line));
        } catch (e) {
          print("JSON Error: $e | Line: $line");
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

  void refreshDevices() {
    sendCommand("get_devices");
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MicRouter PC',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: Colors.grey[900],
        colorScheme: ColorScheme.dark(
          primary: Colors.cyanAccent,
          secondary: Colors.purpleAccent,
          surface: Colors.grey[850]!,
        ),
      ),
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
            backgroundColor: Colors.black26,
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
          const VerticalDivider(thickness: 1, width: 1, color: Colors.white10),

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
                color: Colors.black45,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: Colors.white12),
                boxShadow: [
                  BoxShadow(color: Colors.black.withOpacity(0.3), blurRadius: 10, offset: const Offset(0, 4))
                ]
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(20),
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: controller.currentVolume.clamp(0.0, 1.0),
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(colors: [Colors.cyanAccent, Colors.purpleAccent]),
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
                color: controller.status.contains("running") ? Colors.green.withOpacity(0.2) : Colors.white10,
                borderRadius: BorderRadius.circular(20)
              ),
              child: Text(
                "STATUS: ${controller.status.toUpperCase()}",
                style: TextStyle(
                  letterSpacing: 1.2,
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: controller.status.contains("running") ? Colors.greenAccent : Colors.grey
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
          const Divider(color: Colors.white10),
          const SizedBox(height: 10),

          const Text("System Logs:", style: TextStyle(color: Colors.grey, fontSize: 12)),
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(8),
            height: 120,
            decoration: BoxDecoration(
              color: Colors.black87,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.white10)
            ),
            child: ListView.builder(
              controller: _scrollController,
              itemCount: controller.logs.length,
              itemBuilder: (ctx, i) {
                return Text(
                  ">> ${controller.logs[i]}",
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11, color: Colors.white70),
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
            color: Colors.white.withOpacity(0.05),
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
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                      filled: true,
                      fillColor: Colors.black26
                    ),
                    dropdownColor: Colors.grey[850],
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
            color: Colors.white.withOpacity(0.05),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
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
