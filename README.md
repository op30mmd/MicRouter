# MicRouter

**MicRouter** is a low-latency tool that transforms your Android device into a high-quality microphone for your Windows PC. It routes audio from your phone's microphone to your PC via USB.

## Project Structure

This project is a monorepo containing three main components:

- **`android/`**: The Android application that captures microphone audio and sends it over TCP.
- **`desktop/`**: The PC client application for Windows. It consists of two parts:
    - **`desktop/frontend/`**: A Flutter application that provides the user interface.
    - **`desktop/backend/`**: A Python server that communicates with the Android app and the Flutter frontend.
- **`.github/`**: Contains GitHub Actions workflows for CI/CD.

## Features
- **Low Latency:** Uses raw TCP over ADB (USB) for near-instant audio.
- **Background Service:** Android app runs as a Foreground Service.
- **Digital Gain:** Boost volume up to 500% via the PC Client.
- **Visual Monitoring:** Real-time connection status and audio level visualization.

## Prerequisites

1.  **Android Device:** USB Debugging enabled.
2.  **Windows PC:**
    *   Python 3.10+ installed.
    *   Flutter SDK installed.
3.  **ADB:** Added to your system's PATH.
4.  **(Optional) VB-Audio Cable:** For using the audio as a microphone input in other applications.

## Development Setup

To run this project in a development environment, you need to set up and run the Android app, the Python backend, and the Flutter frontend.

### 1. Android App
1.  Navigate to the `android/` directory.
2.  Open the project in Android Studio or build it from the command line:
    ```bash
    ./gradlew installDebug
    ```
3.  Launch the app on your Android device and grant the necessary permissions.

### 2. PC Application (Desktop)

The PC application consists of a Python backend and a Flutter frontend that must be run separately for development.

**A. Run the Backend (Python):**
1.  Install the required Python packages:
    ```bash
    pip install -r desktop/backend/requirements.txt
    ```
2.  Start the backend server:
    ```bash
    python desktop/backend/backend.py
    ```

**B. Run the Frontend (Flutter):**
1.  Navigate to the frontend directory:
    ```bash
    cd desktop/frontend
    ```
2.  Install the Flutter dependencies:
    ```bash
    flutter pub get
    ```
3.  Run the Flutter application:
    ```bash
    flutter run -d windows
    ```
The Flutter app will launch and automatically connect to the running Python backend.