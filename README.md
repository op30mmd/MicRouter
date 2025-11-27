# MicRouter

**MicRouter** is a low-latency tool that transforms your Android device into a high-quality microphone for your Windows PC. It routes audio from your phone's microphone to your PC via a USB connection with minimal latency.

## Prebuilt Releases

For most users, it is recommended to download the latest prebuilt binaries from the **[GitHub Releases](https://github.com/op30mmd/MicRouter/releases)** page. This is the easiest way to get started with MicRouter.

The project is configured with a CI/CD workflow that automatically builds and releases new versions of the Android and Windows applications whenever changes are pushed to the `master` branch.

## Architecture Overview

The MicRouter system consists of three main components that work together:

1.  **Android App (`android/`)**: A native Android application that runs as a foreground service. It captures raw audio from the microphone, can apply audio effects, and streams the data over a TCP socket.
2.  **PC Backend (`desktop/backend/`)**: A Python server that listens for a connection from the Flutter UI and the Android app. It forwards commands from the UI, receives the audio stream, applies a digital gain, and plays the audio through a selected sound device on the PC.
3.  **PC Frontend (`desktop/frontend/`)**: A Flutter desktop application for Windows that provides a user interface for controlling the system. It allows the user to select an output device, start/stop the stream, and adjust the digital gain.

The communication flow is as follows:
- The **Android app** starts a TCP server (default port 6000) and waits for a connection.
- The **PC Backend** starts a TCP server for the UI (default port 5000).
- When the user clicks "Start" in the **PC Frontend**, it sends a `start` command to the **PC Backend**.
- The **PC Backend** then uses `adb forward` to create a connection to the **Android app** over USB and connects to its TCP server.
- The **Android app** sends the raw audio data to the **PC Backend**, which processes it and plays it on the PC.

## Features

- **Low Latency Streaming**: Audio is sent as a raw PCM stream over a TCP socket created via `adb` for high speed.
- **Background Operation**: The Android app runs as a foreground service, allowing it to work even when the app is in the background or the screen is off.
- **Digital Effects & Processing**:
    - **Digital Gain**: Boost the microphone volume from the PC client.
    - **Hardware Noise Suppression**: Utilizes the device's built-in noise suppressor for cleaner audio (if available).
    - **Hardware Echo Cancellation**: Utilizes the device's built-in acoustic echo canceler (if available).
    - **Software Noise Gate**: A simple noise gate on the Android app silences audio below a certain volume threshold to reduce background noise.
- **Real-time Monitoring**: The PC client provides a real-time audio visualizer and status updates.

## Prerequisites

1.  **Android Device**: With USB Debugging enabled.
2.  **Windows PC**:
    *   Python 3.10+ installed.
    *   Flutter SDK installed.
3.  **ADB**: Must be installed and accessible from your system's PATH.
4.  **(Optional) VB-Audio Cable**: To route the audio as a virtual microphone input to other applications.

## Development Setup

To run this project in a development environment, you need to set up and run the Android app, the Python backend, and the Flutter frontend separately.

### 1. Android App
1.  Open the `android/` directory in Android Studio.
2.  Build and run the project on your connected Android device.
3.  Alternatively, you can install it using Gradle:
    ```bash
    cd android && ./gradlew installDebug
    ```
4.  Launch the app and grant the required microphone permissions. You can also configure audio settings from within the app.

### 2. PC Application (Desktop)

**A. Run the Backend (Python):**
1.  Install the required Python packages:
    ```bash
    pip install -r desktop/backend/requirements.txt
    ```
2.  Start the backend server:
    ```bash
    python desktop/backend/backend.py
    ```
    The backend will start and wait for a connection from the Flutter frontend.

**B. Run the Frontend (Flutter):**
1.  Navigate to the frontend directory:
    ```bash
    cd desktop/frontend
    ```
2.  Install the Flutter dependencies:
    ```bash
    flutter pub get
    ```
3.  Run the Flutter application on Windows:
    ```bash
    flutter run -d windows
    ```
The Flutter app will launch and automatically connect to the running Python backend.

## Configuration

### Android App Settings

The Android app provides a settings screen where you can configure the following:
- **Server Port**: The TCP port for the audio stream (default: `6000`).
- **Sample Rate**: The audio sample rate in Hz (default: `48000`).
- **Enable HW Suppressor**: Toggle hardware noise suppression (default: `true`).
- **Noise Gate Threshold**: The RMS threshold for the software noise gate (default: `100`).

### PC Backend API

The Python backend communicates with the Flutter frontend via JSON commands on port `5000`.

- **`{"command": "get_devices"}`**:
  Requests a list of available audio output devices on the PC. The backend responds with a `{"type": "devices", "payload": [...]}` message.

- **`{"command": "start", "device_name": "...", "port": 6000}`**:
  Tells the backend to start the audio stream. It requires the name of the target output device and the port for the Android app.

- **`{"command": "stop"}`**:
  Stops the audio stream.

- **`{"command": "set_gain", "value": 1.5}`**:
  Sets the digital gain (volume multiplier).
