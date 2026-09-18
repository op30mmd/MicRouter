import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:yaru/yaru.dart';

import 'backend_controller.dart';

/// Material/Yaru shell — used on Linux and as fallback on any non-Windows
/// platform. Intentionally untouched by the Fluent migration: Windows uses
/// [FluentMicRouterApp] from `fluent_shell.dart` instead.
class MaterialMicRouterApp extends StatelessWidget {
  const MaterialMicRouterApp({super.key});

  ThemeData _buildTheme(bool isDark) {
    final isLinux = Platform.isLinux;

    if (isLinux) {
      return isDark ? yaruDark : yaruLight;
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
      home: const MaterialHomeScreen(),
    );
  }
}

class MaterialHomeScreen extends StatefulWidget {
  const MaterialHomeScreen({super.key});

  @override
  State<MaterialHomeScreen> createState() => _MaterialHomeScreenState();
}

class _MaterialHomeScreenState extends State<MaterialHomeScreen> {
  int _selectedIndex = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: _selectedIndex,
            backgroundColor:
                Theme.of(context).colorScheme.surface.withOpacity(0.5),
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
          VerticalDivider(
              thickness: 1,
              width: 1,
              color: Theme.of(context).dividerColor.withOpacity(0.1)),
          Expanded(
            child: _selectedIndex == 0
                ? const MaterialRouterView()
                : const MaterialSettingsView(),
          ),
        ],
      ),
    );
  }
}

class MaterialRouterView extends StatefulWidget {
  const MaterialRouterView({super.key});

  @override
  State<MaterialRouterView> createState() => _MaterialRouterViewState();
}

class _MaterialRouterViewState extends State<MaterialRouterView> {
  final ScrollController _scrollController = ScrollController();

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
          Text("Dashboard",
              style: Theme.of(context)
                  .textTheme
                  .headlineMedium
                  ?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 40),
          Center(
            child: Container(
              height: 80,
              width: double.infinity,
              constraints: const BoxConstraints(maxWidth: 700),
              decoration: BoxDecoration(
                  color: Theme.of(context)
                      .colorScheme
                      .surfaceContainerHighest
                      .withOpacity(0.5),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(
                      color:
                          Theme.of(context).dividerColor.withOpacity(0.1)),
                  boxShadow: [
                    BoxShadow(
                        color: Colors.black.withOpacity(0.1),
                        blurRadius: 10,
                        offset: const Offset(0, 4))
                  ]),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(20),
                child: ValueListenableBuilder<double>(
                  valueListenable: controller.volumeNotifier,
                  builder: (context, volume, _) {
                    return FractionallySizedBox(
                      alignment: Alignment.centerLeft,
                      widthFactor: volume.clamp(0.0, 1.0),
                      child: Container(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(colors: [
                            Theme.of(context).colorScheme.primary,
                            Theme.of(context).colorScheme.secondary,
                          ]),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
          ),
          const SizedBox(height: 15),

          // Status Text
          Center(
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                  color: controller.status.contains("running")
                      ? Colors.green.withOpacity(0.2)
                      : Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(20)),
              child: Text("STATUS: ${controller.status.toUpperCase()}",
                  style: TextStyle(
                      letterSpacing: 1.2,
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: controller.status.contains("running")
                          ? (Theme.of(context).brightness == Brightness.dark
                              ? Colors.greenAccent
                              : Colors.green[700])
                          : Theme.of(context).hintColor)),
            ),
          ),
          const SizedBox(height: 60),
          Center(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: (controller.status == "running" ||
                          controller.selectedDevice == null)
                      ? null
                      : controller.startStreaming,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text("START ROUTING"),
                  style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 32, vertical: 22),
                      textStyle: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold),
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12))),
                ),
                const SizedBox(width: 20),
                OutlinedButton.icon(
                  onPressed: controller.status == "running"
                      ? controller.stopStreaming
                      : null,
                  icon: const Icon(Icons.stop),
                  label: const Text("STOP"),
                  style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 32, vertical: 22),
                      foregroundColor: Colors.redAccent,
                      side: const BorderSide(color: Colors.redAccent),
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12))),
                ),
              ],
            ),
          ),
          const Spacer(),
          Divider(color: Theme.of(context).dividerColor.withOpacity(0.1)),
          const SizedBox(height: 10),
          Text("System Logs:",
              style: TextStyle(
                  color: Theme.of(context).hintColor, fontSize: 12)),
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(8),
            height: 120,
            decoration: BoxDecoration(
                color: Theme.of(context)
                    .colorScheme
                    .surfaceContainerHighest
                    .withOpacity(0.3),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                    color: Theme.of(context).dividerColor.withOpacity(0.1))),
            child: ListView.builder(
              controller: _scrollController,
              itemCount: controller.logs.length,
              itemBuilder: (ctx, i) {
                return Text(
                  ">> ${controller.logs[i]}",
                  style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 11,
                      color: Theme.of(context)
                          .colorScheme
                          .onSurface
                          .withOpacity(0.7)),
                );
              },
            ),
          )
        ],
      ),
    );
  }
}

class MaterialSettingsView extends StatelessWidget {
  const MaterialSettingsView({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<BackendController>();

    return Padding(
      padding: const EdgeInsets.all(40.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text("Audio Settings",
              style: Theme.of(context)
                  .textTheme
                  .headlineMedium
                  ?.copyWith(fontWeight: FontWeight.bold)),
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
                      const Text("Output Device",
                          style: TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w500)),
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
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 12),
                        filled: true,
                        fillColor: Theme.of(context).colorScheme.surface),
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
                      Icon(Icons.dark_mode,
                          color: Theme.of(context).colorScheme.primary),
                      const SizedBox(width: 15),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("Dark Mode",
                                style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w500)),
                            Text("Switch between light and dark UI",
                                style: TextStyle(
                                    fontSize: 12, color: Colors.grey)),
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
                            Text("AI Noise Cancellation",
                                style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w500)),
                            Text(
                                "Reduces background noise using RNNoise",
                                style: TextStyle(
                                    fontSize: 12, color: Colors.grey)),
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
                      const Icon(Icons.volume_up,
                          color: Colors.cyanAccent),
                      const SizedBox(width: 15),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                                "Digital Gain (Boost): ${controller.gainValue.toStringAsFixed(1)}x",
                                style: const TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w500)),
                            Slider(
                              value: controller.gainValue,
                              min: 1.0,
                              max: 5.0,
                              divisions: 40,
                              activeColor: Colors.cyanAccent,
                              label:
                                  "${controller.gainValue.toStringAsFixed(1)}x",
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
