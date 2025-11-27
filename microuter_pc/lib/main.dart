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
  List<String> logs = [];
  List<String> devices = [];
  String? selectedDevice;

  BackendController() {
    _startEmbeddedBackend();
  }

  void _startEmbeddedBackend() async {
    String exePath = Platform.resolvedExecutable;
    String dir = File(exePath).parent.path;
    String backendExe = p.join(dir, 'microuter_engine.exe');

    logs.add("Looking for backend at: $backendExe");
    notifyListeners();

    if (await File(backendExe).exists()) {
      try {
        _pythonProcess = await Process.start(backendExe, []);
        logs.add("Backend started successfully.");
        // _pythonProcess!.stdout.transform(utf8.decoder).listen((data) => print("PY: $data"));
      } catch (e) {
        logs.add("Failed to launch backend: $e");
      }
    } else {
      logs.add("Backend EXE not found. (Dev mode?)");
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
              } catch (e) {
                // Ignore partial JSON chunks
              }
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
        if (devices.isNotEmpty) {
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
}

// --- UI LAYOUT (No changes needed here) ---
class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MicRouter PC',
      theme: ThemeData.dark(useMaterial3: true),
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
          NavigationRail(
            selectedIndex: 0,
            destinations: const [
              NavigationRailDestination(icon: Icon(Icons.mic), label: Text('Home')),
              NavigationRailDestination(icon: Icon(Icons.settings), label: Text('Settings')),
            ],
            onDestinationSelected: (int index) {},
          ),
          const VerticalDivider(thickness: 1, width: 1),

          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(32.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    height: 50,
                    width: 300,
                    decoration: BoxDecoration(
                      color: Colors.grey[800],
                      borderRadius: BorderRadius.circular(25),
                    ),
                    child: FractionallySizedBox(
                      alignment: Alignment.centerLeft,
                      widthFactor: controller.currentVolume,
                      child: Container(
                        decoration: BoxDecoration(
                          gradient: const LinearGradient(colors: [Colors.cyan, Colors.purple]),
                          borderRadius: BorderRadius.circular(25),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 40),

                  Text(controller.status.toUpperCase(), style: Theme.of(context).textTheme.headlineMedium),
                  const SizedBox(height: 20),

                  DropdownButton<String>(
                    value: controller.selectedDevice,
                    hint: const Text("Select an Output Device"),
                    items: controller.devices.map((String value) {
                      return DropdownMenuItem<String>(
                        value: value,
                        child: Text(value),
                      );
                    }).toList(),
                    onChanged: controller.selectDevice,
                  ),
                  const SizedBox(height: 20),

                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      FilledButton.icon(
                        onPressed: controller.startStreaming,
                        icon: const Icon(Icons.play_arrow),
                        label: const Text("Start Server"),
                        style: FilledButton.styleFrom(padding: const EdgeInsets.all(20)),
                      ),
                      const SizedBox(width: 20),
                      OutlinedButton.icon(
                        onPressed: controller.stopStreaming,
                        icon: const Icon(Icons.stop),
                        label: const Text("Stop"),
                        style: OutlinedButton.styleFrom(padding: const EdgeInsets.all(20)),
                      ),
                    ],
                  ),

                  const SizedBox(height: 40),
                  Expanded(
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: ListView.builder(
                        itemCount: controller.logs.length,
                        itemBuilder: (ctx, i) => Text(
                          ">> ${controller.logs[i]}",
                          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                        ),
                      ),
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
