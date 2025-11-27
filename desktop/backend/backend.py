import socket
import threading
import json
import time
import struct
import pyaudio
import numpy as np
import subprocess
import sys
import os 
from denoiser import RNNoise

# CONFIGURATION
FLUTTER_PORT = 5000
ANDROID_PORT = 6000

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
        self.device_map = {}
        self.current_gain = 1.0
        self.rnnoise = None
        self.use_rnnoise = False

        self.start_parent_watchdog()

    def start_parent_watchdog(self):
        """Kills this process if the parent process (Flutter) closes the stdin pipe."""
        def watchdog():
            while True:
                try:
                    if not sys.stdin.read(1):
                        self.cleanup()
                        os._exit(0) 
                except Exception:
                    self.cleanup()
                    os._exit(0)
        
        t = threading.Thread(target=watchdog, daemon=True)
        t.start()

    def start(self):
        print(f"[*] Python Backend listening on {FLUTTER_PORT}...")
        self._scan_devices()
        try:
            while True:
                self.client_socket, addr = self.server_socket.accept()
                print(f"[*] UI Connected: {addr}")
                self.handle_ui_connection()
        except KeyboardInterrupt:
            self.cleanup()

    def _scan_devices(self):
        self.device_map = {}
        try:
            info = self.p.get_host_api_info_by_index(0)
            num_devices = info.get("deviceCount")
            for i in range(num_devices):
                device_info = self.p.get_device_info_by_host_api_device_index(0, i)
                if device_info.get("maxOutputChannels") > 0:
                    name = device_info.get("name")
                    self.device_map[name] = i
        except Exception: pass

    def send_to_flutter(self, data_dict):
        if self.client_socket:
            try:
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
            except Exception: break
        self.client_socket = None

    def process_command(self, cmd):
        command = cmd.get('command')
        
        if command == 'get_devices':
            self._scan_devices()
            self.send_to_flutter({"type": "devices", "payload": list(self.device_map.keys())})

        elif command == 'set_gain':
            try: self.current_gain = float(cmd.get('value', 1.0))
            except: pass
            
        elif command == 'toggle_rnnoise':
            self.use_rnnoise = cmd.get('value', False)
            if self.use_rnnoise and self.rnnoise is None:
                try:
                    self.rnnoise = RNNoise()
                    self.send_to_flutter({"type": "log", "message": "[*] AI Denoising Enabled"})
                except Exception as e:
                    self.send_to_flutter({"type": "error", "message": f"RNNoise Error: {e}"})
                    self.use_rnnoise = False
            elif not self.use_rnnoise:
                self.send_to_flutter({"type": "log", "message": "[*] AI Denoising Disabled"})

        elif command == 'start':
            if not self.is_streaming:
                if self.use_rnnoise and self.rnnoise is None:
                    try: self.rnnoise = RNNoise()
                    except: pass
                self.is_streaming = True
                self.audio_thread = threading.Thread(
                    target=self.audio_stream_logic,
                    args=(cmd.get('device_name'), cmd.get('port', 6000))
                )
                self.audio_thread.start()
        
        elif command == 'stop':
            self.is_streaming = False

    def setup_adb(self, port):
        self.send_to_flutter({"type": "log", "message": "[*] Setting up ADB..."})
        try:
            subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return True
        except Exception as e:
            self.send_to_flutter({"type": "error", "message": f"ADB Error: {e}"})
            return False

    def audio_stream_logic(self, device_name, port):
        sock = None
        stream = None
        
        if not self.setup_adb(port):
            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "failed"})
            return

        try:
            self.send_to_flutter({"type": "status", "payload": "connecting"})
            
            # --- CONNECTION RETRY LOOP (THE FIX) ---
            connected = False
            attempts = 0
            max_retries = 20 # Try for ~10 seconds
            
            while self.is_streaming and not connected and attempts < max_retries:
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(2) # Short timeout for connection attempt
                    sock.connect(('127.0.0.1', port))
                    connected = True
                    sock.settimeout(None) # Disable timeout for streaming
                except Exception:
                    attempts += 1
                    if attempts % 2 == 0: # Only log every other attempt to reduce spam
                        self.send_to_flutter({"type": "log", "message": f"[*] Waiting for phone... ({attempts}/{max_retries})"})
                    time.sleep(0.5)
                    if sock: sock.close()

            if not connected:
                raise Exception("Could not connect to phone. Is the app running?")

            self.send_to_flutter({"type": "log", "message": "[*] Connected! Waiting for stream..."})

            # --- HANDSHAKE ---
            header = sock.recv(4)
            if len(header) < 4:
                raise Exception("Phone disconnected during handshake.")
            
            sample_rate = struct.unpack('>I', header)[0]
            self.send_to_flutter({"type": "log", "message": f"[*] Sample Rate: {sample_rate} Hz"})

            # --- AUDIO DEVICE ---
            device_index = self.device_map.get(device_name)
            if device_index is None:
                device_index = self.p.get_default_output_device_info()["index"]

            stream = self.p.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=sample_rate,
                output=True,
                output_device_index=device_index,
                frames_per_buffer=1024
            )

            self.send_to_flutter({"type": "status", "payload": "running"})

            # --- STREAM LOOP ---
            while self.is_streaming:
                data = sock.recv(1024)
                if not data: break
                
                # --- RNNOISE INTEGRATION ---
                final_data = data
                
                if self.use_rnnoise and self.rnnoise:
                    try:
                        # Feed raw bytes, get clean bytes back
                        processed = self.rnnoise.process(data)
                        if processed:
                            final_data = processed
                        else:
                            # RNNoise buffers data internally until it has enough for a frame.
                            # If it returns empty, we must NOT write to speakers yet to avoid glitches.
                            continue 
                    except Exception as e:
                        print(f"RNNoise fail: {e}")

                # --- VISUALIZER & PLAYBACK ---
                # Use final_data for playback, but raw 'data' for visualizer?
                # Actually, visualizing the CLEAN audio is better:
                audio_data = np.frombuffer(final_data, dtype=np.int16)
                
                # Gain
                if self.current_gain != 1.0:
                    audio_data = np.clip(audio_data * self.current_gain, -32768, 32767).astype(np.int16)
                    final_data = audio_data.tobytes()

                # Send volume to Flutter
                if len(audio_data) > 0:
                    rms = np.sqrt(np.mean(audio_data.astype(float)**2))
                    self.send_to_flutter({"type": "volume", "value": min(rms / 2000, 1.0)})
                
                stream.write(final_data)

        except Exception as e:
            self.send_to_flutter({"type": "error", "message": str(e)})
        finally:
            if stream: 
                stream.stop_stream()
                stream.close()
            if sock: 
                sock.close()
            
            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "stopped"})
            self.send_to_flutter({"type": "volume", "value": 0.0})

    def cleanup(self):
        self.is_streaming = False
        self.p.terminate()
        self.server_socket.close()

if __name__ == "__main__":
    BackendServer().start()
