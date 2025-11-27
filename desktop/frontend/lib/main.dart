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
  double currentVolume = 0.0; // Visualizer value (0.0 - 1.0)
  double gainValue = 1.0;     // Slider value (1.0 - 5.0)

  List<String> logs = [];
  List<String> devices = [];
  String? selectedDevice;

  BackendController() {
    _startEmbeddedBackend();
  }

  void _startEmbeddedBackend() async {
    String exePath = Platform.resolvedExecutable;
    String dir = File(exePath).parent.path;
    String scriptPath = p.join(dir, 'backend', 'backend.py');

    logs.add("Looking for script at: $scriptPath");
    notifyListeners();

    if (await File(scriptPath).exists()) {
      try {
        _pythonProcess = await Process.start('python', [scriptPath]);
        logs.add("Python backend started.");

        _pythonProcess!.stderr.transform(utf8.decoder).listen((data) {
             print("PY_ERR: $data");
             logs.add("PY_ERR: $data");
             notifyListeners();
        });
      } catch (e) {
        logs.add("Failed to launch python: $e");
      }
    } else {
      logs.add("backend.py not found (Dev mode?)");
    }

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
      sendCommand("get_devices");

      _socket!.cast<List<int>>().transform(utf8.decoder).listen(
        (data) {
          for (var line in data.split('\n')) {
            if (line.trim().isNotEmpty) {
              try {
                _handleMessage(jsonDecode(line));
              } catch (e) { /* ignore partial packets */ }
            }
          }
        },
        onDone: () {
          status = "Backend Disconnected";
          notifyListeners();
        },
        onError: (e) {
          status = "Connection Error";
          notifyListeners();
        },
      );
    } catch (e) {
      status = "Waiting for Backend...";
      Future.delayed(const Duration(seconds: 2), connectToPython);
      notifyListeners();
    }
  }

  void _handleMessage(Map<String, dynamic> msg) {
    switch (msg['type']) {
      case 'status':
        status = msg['payload'];
        break;
      case 'volume':
        // Visualizer update
        currentVolume = (msg['value'] as num).toDouble();
        break;
      case 'log':
        logs.add(msg['message']);
        break;
      case 'error':
        logs.add("ERROR: ${msg['message']}");
        break;
      case 'devices':
        devices = List<String>.from(msg['payload']);
        if (devices.isNotEmpty && selectedDevice == null) {
          selectedDevice = devices.first;
        }
        break;
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
      logs.add("ERROR: No output device selected!");
      notifyListeners();
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
    // Send command to python to update multiplier
    sendCommand('set_gain', {'value': val});
    notifyListeners();
  }
}

// --- UI LAYOUT ---
class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MicRouter PC',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        colorScheme: ColorScheme.dark(
          primary: Colors.cyanAccent,
          secondary: Colors.purpleAccent,
          surface: Colors.grey[900]!,
        ),
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    return Scaffold(
      body: Row(
        children: [
          // Sidebar
          NavigationRail(
            selectedIndex: 0,
            backgroundColor: Colors.black26,
            destinations: const [
              NavigationRailDestination(icon: Icon(Icons.mic), label: Text('Router')),
              NavigationRailDestination(icon: Icon(Icons.settings), label: Text('Settings')),
            ],
            onDestinationSelected: (int index) {},
          ),
          const VerticalDivider(thickness: 1, width: 1, color: Colors.white10),

          // Main Content
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(40.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text("Android Mic Router", style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 40),

                  // 1. Visualizer
                  Center(
                    child: Container(
                      height: 60,
                      width: double.infinity,
                      constraints: const BoxConstraints(maxWidth: 600),
                      decoration: BoxDecoration(
                        color: Colors.black45,
                        borderRadius: BorderRadius.circular(30),
                        border: Border.all(color: Colors.white12)
                      ),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(30),
                        child: FractionallySizedBox(
                          alignment: Alignment.centerLeft,
                          widthFactor: controller.currentVolume,
                          child: Container(
                            decoration: const BoxDecoration(
                              gradient: LinearGradient(colors: [Colors.cyan, Colors.purpleAccent]),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Center(child: Text(controller.status.toUpperCase(), style: const TextStyle(letterSpacing: 1.5, fontSize: 12, color: Colors.grey))),

                  const SizedBox(height: 40),

                  // 2. Controls Row
                  Row(
                    children: [
                      // Device Dropdown
                      Expanded(
                        flex: 2,
                        child: DropdownButtonFormField<String>(
                          value: controller.selectedDevice,
                          decoration: const InputDecoration(
                            labelText: "Output Device",
                            border: OutlineInputBorder(),
                            filled: true,
                          ),
                          isExpanded: true,
                          items: controller.devices.map((String value) {
                            return DropdownMenuItem<String>(
                              value: value,
                              child: Text(value, overflow: TextOverflow.ellipsis),
                            );
                          }).toList(),
                          onChanged: controller.selectDevice,
                        ),
                      ),
                      const SizedBox(width: 20),

                      // Gain Slider
                      Expanded(
                        flex: 2,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("Volume Boost: ${controller.gainValue.toStringAsFixed(1)}x"),
                            Slider(
                              value: controller.gainValue,
                              min: 1.0,
                              max: 5.0,
                              divisions: 40,
                              label: "${controller.gainValue.toStringAsFixed(1)}x",
                              onChanged: (val) => controller.setGain(val),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 30),

                  // 3. Action Buttons
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      FilledButton.icon(
                        onPressed: controller.status.contains("running") ? null : controller.startStreaming,
                        icon: const Icon(Icons.play_arrow),
                        label: const Text("START RECEIVING"),
                        style: FilledButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 22),
                          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)
                        ),
                      ),
                      const SizedBox(width: 20),
                      OutlinedButton.icon(
                        onPressed: controller.status.contains("running") ? controller.stopStreaming : null,
                        icon: const Icon(Icons.stop),
                        label: const Text("STOP"),
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 22),
                          foregroundColor: Colors.redAccent,
                          side: const BorderSide(color: Colors.redAccent)
                        ),
                      ),
                    ],
                  ),

                  const Spacer(),
                  const Divider(color: Colors.white10),
                  const SizedBox(height: 10),

                  // 4. Logs
                  const Text("Logs:", style: TextStyle(color: Colors.grey, fontSize: 12)),
                  SizedBox(
                    height: 100,
                    child: ListView.builder(
                      reverse: true, // Auto-scroll to bottom
                      itemCount: controller.logs.length,
                      itemBuilder: (ctx, i) {
                        // Reverse index for list view
                        final logIndex = controller.logs.length - 1 - i;
                        return Text(
                          ">> ${controller.logs[logIndex]}",
                          style: const TextStyle(fontFamily: 'monospace', fontSize: 11, color: Colors.white70),
                        );
                      },
                    ),
                  )
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
