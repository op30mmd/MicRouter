import socket
import threading
import json
import time
import struct
import pyaudio
import numpy as np
import subprocess

# CONFIGURATION
FLUTTER_PORT = 5000  # Port to talk to UI

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
        self.device_map = self._get_device_map()

    def _get_device_map(self):
        device_map = {}
        info = self.p.get_host_api_info_by_index(0)
        num_devices = info.get("deviceCount")
        for i in range(num_devices):
            device_info = self.p.get_device_info_by_host_api_device_index(0, i)
            if device_info.get("maxOutputChannels") > 0:
                name = device_info.get("name")
                device_map[name] = i
        return device_map

    def start(self):
        print(f"[*] Python Backend listening on {FLUTTER_PORT}...")
        try:
            while True:
                self.client_socket, addr = self.server_socket.accept()
                print(f"[*] UI Connected: {addr}")
                self.handle_ui_connection()
        except KeyboardInterrupt:
            print("[*] Shutting down...")
        finally:
            self.cleanup()

    def send_to_flutter(self, data_dict):
        if self.client_socket:
            try:
                message = json.dumps(data_dict) + "\n"
                self.client_socket.sendall(message.encode('utf-8'))
            except (ConnectionResetError, BrokenPipeError):
                print("[!] Flutter connection lost.")
                self.client_socket = None # Mark as disconnected

    def handle_ui_connection(self):
        buffer = ""
        while self.client_socket:
            try:
                data = self.client_socket.recv(1024).decode('utf-8')
                if not data: break

                buffer += data
                while "\n" in buffer:
                    message, buffer = buffer.split("\n", 1)
                    if message:
                        self.process_command(json.loads(message))
            except json.JSONDecodeError:
                self.send_to_flutter({"type": "error", "message": "Invalid JSON received."})
            except Exception as e:
                print(f"[!] UI Connection Error: {e}")
                break # Exit loop on error
        print("[*] UI Disconnected")
        self.client_socket = None
        if self.is_streaming: # Stop audio if UI disconnects
            self.is_streaming = False


    def process_command(self, cmd):
        print(f"[*] Received: {cmd}")
        command = cmd.get('command')

        if command == 'start':
            if not self.is_streaming:
                self.is_streaming = True
                self.audio_thread = threading.Thread(
                    target=self.audio_stream_logic,
                    args=(cmd.get('port', 6000), cmd.get('device_name')),
                    daemon=True
                )
                self.audio_thread.start()

        elif command == 'stop':
            if self.is_streaming:
                self.is_streaming = False
                self.send_to_flutter({"type": "status", "payload": "stopped"})

        elif command == 'get_devices':
            devices = list(self.device_map.keys())
            self.send_to_flutter({"type": "devices", "payload": devices})

    def setup_adb(self, port):
        self.send_to_flutter({"type": "log", "message": "[*] Initializing ADB..."})
        try:
            # Using absolute path for adb might be more robust
            subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.send_to_flutter({"type": "log", "message": "[*] ADB Forwarding active."})
            return True
        except Exception as e:
            self.send_to_flutter({"type": "error", "message": f"ADB Error: {e}. Is phone connected?"})
            return False

    def audio_stream_logic(self, android_port, device_name):
        android_socket = None
        audio_stream = None

        if not self.setup_adb(android_port):
            self.send_to_flutter({"type": "status", "payload": "failed"})
            return

        try:
            # Get device index from name
            device_index = self.device_map.get(device_name)
            if device_index is None:
                raise ValueError(f"Output device '{device_name}' not found.")

            self.send_to_flutter({"type": "status", "payload": "connecting"})
            android_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.send_to_flutter({"type": "log", "message": f"[*] Connecting to 127.0.0.1:{android_port}..."})
            android_socket.connect(('127.0.0.1', android_port))
            self.send_to_flutter({"type": "log", "message": "[*] Connected to Phone!"})

            # Receive and unpack sample rate header
            raw_header = android_socket.recv(4)
            if len(raw_header) < 4:
                raise ConnectionAbortedError("Phone disconnected before sending sample rate. Is the sample rate supported?")

            sample_rate = struct.unpack('>I', raw_header)[0]
            self.send_to_flutter({"type": "log", "message": f"[*] Sample Rate: {sample_rate} Hz"})

            # Setup PyAudio output stream
            audio_stream = self.p.open(format=pyaudio.paInt16,
                                     channels=1,
                                     rate=sample_rate,
                                     output=True,
                                     output_device_index=device_index,
                                     frames_per_buffer=1024)

            self.send_to_flutter({"type": "status", "payload": "running"})
            self.send_to_flutter({"type": "log", "message": "[*] Streaming audio..."})

            while self.is_streaming:
                data = android_socket.recv(1024)
                if not data:
                    self.send_to_flutter({"type": "log", "message": "[*] Stream ended by phone."})
                    break

                # Process for UI visualizer
                audio_data = np.frombuffer(data, dtype=np.int16)
                rms = np.sqrt(np.mean(audio_data.astype(float)**2))
                normalized_vol = min(rms / 1000, 1.0)
                self.send_to_flutter({"type": "volume", "value": normalized_vol})

                # Write to speaker
                audio_stream.write(data)

        except Exception as e:
            error_message = f"[!] Stream Error: {e}"
            print(error_message)
            self.send_to_flutter({"type": "error", "message": error_message})
        finally:
            if android_socket:
                android_socket.close()
            if audio_stream:
                audio_stream.stop_stream()
                audio_stream.close()

            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "stopped"})
            self.send_to_flutter({"type": "log", "message": "[*] Stream stopped."})

    def cleanup(self):
        print("[*] Cleaning up resources...")
        self.is_streaming = False
        if self.audio_thread and self.audio_thread.is_alive():
            self.audio_thread.join()
        if self.client_socket:
            self.client_socket.close()
        if self.server_socket:
            self.server_socket.close()
        self.p.terminate()
        print("[*] Backend server shut down.")

if __name__ == "__main__":
    server = BackendServer()
    server.start()
