import socket
import threading
import json
import time
import struct
import pyaudio
import numpy as np
import subprocess
import sys

# CONFIGURATION
FLUTTER_PORT = 5000  # Port to talk to UI (Local Control)
ANDROID_PORT = 6000  # Port to receive Audio (From Phone)

class BackendServer:
    def __init__(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(('127.0.0.1', FLUTTER_PORT))
        self.server_socket.listen(1)

        self.client_socket = None
        self.is_streaming = False
        self.audio_thread = None

        self.p = pyaudio.PyAudio()
        self.device_map = {} # Maps "Device Name" -> Index

    def start(self):
        print(f"[*] Python Backend listening on {FLUTTER_PORT}...")
        # Scan devices immediately on startup
        self._scan_devices()

        try:
            while True:
                self.client_socket, addr = self.server_socket.accept()
                print(f"[*] UI Connected: {addr}")
                self.handle_ui_connection()
        except KeyboardInterrupt:
            self.cleanup()

    def _scan_devices(self):
        """Scans audio output devices and stores mapping"""
        self.device_map = {}
        try:
            info = self.p.get_host_api_info_by_index(0)
            num_devices = info.get("deviceCount")
            for i in range(num_devices):
                device_info = self.p.get_device_info_by_host_api_device_index(0, i)
                if device_info.get("maxOutputChannels") > 0:
                    name = device_info.get("name")
                    self.device_map[name] = i
        except Exception as e:
            print(f"[!] Error scanning devices: {e}")

    def send_to_flutter(self, data_dict):
        if self.client_socket:
            try:
                # JSON Lines format
                message = json.dumps(data_dict) + "\n"
                self.client_socket.sendall(message.encode('utf-8'))
            except:
                self.client_socket = None

    def handle_ui_connection(self):
        buffer = ""
        while self.client_socket:
            try:
                data = self.client_socket.recv(1024).decode('utf-8')
                if not data: break

                buffer += data
                while "\n" in buffer:
                    message, buffer = buffer.split("\n", 1)
                    if message.strip():
                        self.process_command(json.loads(message))
            except Exception as e:
                print(f"[!] Connection Error: {e}")
                break
        print("[*] UI Disconnected")

    def process_command(self, cmd):
        command = cmd.get('command')

        if command == 'get_devices':
            # Refresh list and send to UI
            self._scan_devices()
            device_list = list(self.device_map.keys())
            self.send_to_flutter({"type": "devices", "payload": device_list})

        elif command == 'start':
            if not self.is_streaming:
                self.is_streaming = True
                device_name = cmd.get('device_name')
                port = cmd.get('port', 6000)

                self.audio_thread = threading.Thread(
                    target=self.audio_stream_logic,
                    args=(device_name, port)
                )
                self.audio_thread.start()

        elif command == 'stop':
            self.is_streaming = False
            # The audio thread will see this flag and exit

    def setup_adb(self, port):
        self.send_to_flutter({"type": "log", "message": "[*] Initializing ADB..."})
        try:
            # We use shell=True to find adb in PATH, or you can bundle adb.exe
            subprocess.run(
                ["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE
            )
            self.send_to_flutter({"type": "log", "message": "[*] ADB Forwarding active."})
            return True
        except Exception as e:
            self.send_to_flutter({"type": "error", "message": f"ADB Error: Is phone connected? ({e})"})
            return False

    def audio_stream_logic(self, device_name, port):
        sock = None
        stream = None

        # 1. Setup ADB
        if not self.setup_adb(port):
            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "failed"})
            return

        try:
            # 2. Connect to Phone
            self.send_to_flutter({"type": "status", "payload": "connecting"})
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5) # 5 second timeout for connection

            self.send_to_flutter({"type": "log", "message": f"[*] Connecting to 127.0.0.1:{port}..."})
            sock.connect(('127.0.0.1', port))
            sock.settimeout(None) # Remove timeout for streaming

            self.send_to_flutter({"type": "log", "message": "[*] Connected! Waiting for header..."})

            # 3. Read Header (Sample Rate)
            header = sock.recv(4)
            if len(header) < 4:
                raise Exception("Connection closed by phone immediately.")

            # Big Endian Unsigned Int (Matches Java DataOutputStream)
            sample_rate = struct.unpack('>I', header)[0]
            self.send_to_flutter({"type": "log", "message": f"[*] Sample Rate: {sample_rate} Hz"})

            # 4. Open Audio Output
            device_index = self.device_map.get(device_name)
            if device_index is None:
                # Fallback to default if device not found
                device_index = self.p.get_default_output_device_info()["index"]
                self.send_to_flutter({"type": "log", "message": "[!] Device not found, using default."})

            stream = self.p.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=sample_rate,
                output=True,
                output_device_index=device_index,
                frames_per_buffer=1024
            )

            self.send_to_flutter({"type": "status", "payload": "running"})
            self.send_to_flutter({"type": "log", "message": "[*] Streaming started."})

            # 5. Streaming Loop
            while self.is_streaming:
                data = sock.recv(1024)
                if not data:
                    self.send_to_flutter({"type": "log", "message": "[*] Stream ended by phone."})
                    break

                # --- Visualizer Calculation ---
                # Convert bytes to numpy array to calculate volume
                audio_data = np.frombuffer(data, dtype=np.int16)
                # Root Mean Square (RMS) amplitude
                rms = np.sqrt(np.mean(audio_data.astype(float)**2))
                # Normalize (1000 is an arbitrary scaling factor for visibility)
                normalized_vol = min(rms / 1500, 1.0)

                # Send volume update to UI
                self.send_to_flutter({"type": "volume", "value": normalized_vol})

                # --- Audio Output ---
                stream.write(data)

        except Exception as e:
            self.send_to_flutter({"type": "error", "message": str(e)})
        finally:
            # Cleanup
            if stream:
                stream.stop_stream()
                stream.close()
            if sock:
                sock.close()

            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "stopped"})
            self.send_to_flutter({"type": "volume", "value": 0.0}) # Reset visualizer

    def cleanup(self):
        self.is_streaming = False
        if self.audio_thread:
            self.audio_thread.join(timeout=1)
        self.p.terminate()
        self.server_socket.close()

if __name__ == "__main__":
    BackendServer().start()
