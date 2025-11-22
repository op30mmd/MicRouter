MicRouter - Android Mic to Windows PC

PREREQUISITES:
1. Android Studio / Gradle installed.
2. Python 3.x installed.
3. "adb" command accessible in PATH.
4. Enable USB Debugging on your Android device.

INSTRUCTIONS:

A. BUILD & INSTALL ANDROID APP
1. Open command prompt in "MicRouter/android".
2. Run: ./gradlew installDebug
   (On Windows CMD: gradlew installDebug)
   (This builds the APK and installs it on the connected device)
3. Open the App "MicRouter" on your phone.
4. Grant Microphone permissions if asked.
5. Click "Start Server".

B. RUN PC CLIENT
1. Open command prompt in "MicRouter/pc".
2. Install dependencies: pip install pyaudio
   (Note: You might need "pip install pipwin" then "pipwin install pyaudio" if direct install fails on Windows)
3. Run: python play.py

TROUBLESHOOTING:
- If gradle fails, open "MicRouter/android" in Android Studio and let it sync.
- If python script fails to connect, ensure the App is showing "Status: Listening..." and that ADB is working.

