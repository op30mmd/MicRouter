# MicRouter

**MicRouter** is a low-latency tool that transforms your Android device into a high-quality microphone for your Windows PC using USB tethering/debugging. It features a modern GUI, digital gain control, and automatic connection handling.

## Features
- **Low Latency:** Uses raw TCP over ADB (USB) for near-instant audio.
- **Background Service:** Android app runs as a Foreground Service (screen off support).
- **Virtual Mic Support:** Routes audio directly to VB-Audio Cable.
- **Digital Gain:** Boost volume up to 500% via the PC Client.
- **Visual Monitoring:** Real-time connection status and latency metrics.

## Prerequisites

1. **Android Device:** USB Debugging enabled.
2. **Windows PC:** Python 3.10+ installed.
3. **ADB:** Added to System PATH.
4. **(Optional) VB-Audio Cable:** For using as a microphone input in other apps.

## Installation

### 1. Android App
1. Navigate to `android/` folder.
2. Build with Android Studio OR run `./gradlew installDebug`.
3. Open App and grant permissions.

### 2. PC Client
1. Navigate to `pc/` folder.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
