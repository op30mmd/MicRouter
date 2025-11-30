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
        try:
            self.server_socket.bind(('127.0.0.1', FLUTTER_PORT))
        except OSError:
            print(f"[!] Port {FLUTTER_PORT} is busy. Is the app already running?")
            os._exit(1)

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
        """
        Scans output devices.
        Prioritizes Windows WASAPI (Low Latency) if available.
        """
        self.device_map = {}

        # 1. Find the WASAPI Host API Index
        wasapi_index = -1
        try:
            for i in range(self.p.get_host_api_count()):
                api = self.p.get_host_api_info_by_index(i)
                if "WASAPI" in api.get("name", ""):
                    wasapi_index = i
                    break
        except: pass

        # 2. Get total device count
        try:
            total_devices = self.p.get_device_count()
        except:
            total_devices = 0

        # 3. Scan all devices to build the map
        # We iterate through ALL devices to find valid outputs
        for i in range(total_devices):
            try:
                dev = self.p.get_device_info_by_index(i)

                # Filter for Output devices
                if dev.get("maxOutputChannels") > 0:
                    name = dev.get("name")
                    host_api = dev.get("hostApi")

                    # LOGIC:
                    # If this is a WASAPI device, we definitely want it.
                    # If it's a standard device (MME/DirectSound), we accept it
                    # UNLESS we already found a WASAPI version of it.

                    # Clean the name for matching (WASAPI devices often prepend "Speakers (Realtek...)")
                    clean_name = name

                    if host_api == wasapi_index:
                        # This is a WASAPI device. Always prefer this.
                        # We might overwrite a previous entry with the same name, which is good.
                        self.device_map[name] = i

                    elif name not in self.device_map:
                        # This is a generic device. Only add if we don't have it yet.
                        self.device_map[name] = i

            except Exception: continue

    def send_to_flutter(self, data_dict):
        if self.client_socket:
            try:
                # Append newline to ensure Flutter's LineSplitter catches it
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
            # Remove old rules first to be clean
            subprocess.run(["adb", "forward", "--remove", f"tcp:{port}"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], 
                         check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return True
        except Exception as e:
            self.send_to_flutter({"type": "error", "message": f"ADB Error: {e}"})
            return False

    def _recv_exact(self, sock, n):
        """Receive exactly n bytes from socket with timeout handling"""
        data = b''
        while len(data) < n:
            try:
                chunk = sock.recv(n - len(data))
                if not chunk:
                    return None
                data += chunk
            except socket.timeout:
                return None
            except Exception:
                return None
        return data

    def audio_stream_logic(self, device_name, port):
        sock = None
        stream = None
        
        if not self.setup_adb(port):
            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "failed"})
            return

        try:
            self.send_to_flutter({"type": "status", "payload": "connecting"})
            
            # --- CONNECTION RETRY LOOP ---
            connected = False
            attempts = 0
            max_retries = 20
            
            while self.is_streaming and not connected and attempts < max_retries:
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

                    # --- OPTIMIZATION 1: Small TCP Buffer to prevent lag ---
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)

                    sock.settimeout(2)
                    sock.connect(('127.0.0.1', port))
                    connected = True
                except Exception:
                    attempts += 1
                    if attempts % 2 == 0:
                        self.send_to_flutter({"type": "log", "message": f"[*] Waiting for phone... ({attempts}/{max_retries})"})
                    time.sleep(0.5)
                    if sock: 
                        try: sock.close()
                        except: pass
                        sock = None

            if not connected:
                raise Exception("Could not connect to phone. Is the app running?")

            self.send_to_flutter({"type": "log", "message": "[*] Connected! Performing handshake..."})
            
            # Set timeout for handshake
            sock.settimeout(5)

            # ========== HANDSHAKE PROTOCOL ==========
            
            # Step 1: Receive sample rate
            header = self._recv_exact(sock, 4)
            if not header: raise Exception("Handshake failed (Sample Rate)")
            sample_rate = struct.unpack('>i', header)[0]
            
            if sample_rate <= 0 or sample_rate > 192000:
                raise Exception(f"Invalid sample rate: {sample_rate}")
            
            self.send_to_flutter({"type": "log", "message": f"[*] Sample Rate: {sample_rate} Hz"})

            # Step 2: Send acknowledgment
            sock.sendall(struct.pack('>i', sample_rate))

            # Step 3: Wait for READY signal
            ready_bytes = self._recv_exact(sock, 4)
            if not ready_bytes: raise Exception("Handshake failed (Ready Signal)")
            ready_signal = struct.unpack('>i', ready_bytes)[0]
            
            if ready_signal != 0x52454459: # "REDY"
                raise Exception(f"Invalid ready signal: {hex(ready_signal)}")
            
            self.send_to_flutter({"type": "log", "message": "[*] Handshake complete!"})
            
            # ========== END HANDSHAKE ==========

            # --- AUDIO DEVICE SETUP ---
            device_index = self.device_map.get(device_name)
            if device_index is None:
                device_index = self.p.get_default_output_device_info()["index"]

            # --- OPTIMIZATION 2: Match buffer size to Android (10ms = 480 frames at 48k) ---
            stream = self.p.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=sample_rate,
                output=True,
                output_device_index=device_index,
                frames_per_buffer=480
            )

            self.send_to_flutter({"type": "status", "payload": "running"})

            # --- OPTIMIZATION 3: Flush Startup Lag ---
            # Drain any data that arrived while opening speakers to ensure we start "now"
            sock.settimeout(0.0) # Non-blocking
            try:
                while True:
                    if not sock.recv(4096): break
            except BlockingIOError: pass
            except Exception: pass
            sock.settimeout(10.0) # Restore blocking

            self.send_to_flutter({"type": "log", "message": "[*] Streaming audio..."})

            # --- STREAM LOOP ---
            consecutive_errors = 0
            max_consecutive_errors = 5
            
            while self.is_streaming:
                try:
                    # Read length prefix (4 bytes)
                    length_bytes = self._recv_exact(sock, 4)
                    if not length_bytes:
                        self.send_to_flutter({"type": "log", "message": "[*] Connection closed by phone"})
                        break
                    
                    length = struct.unpack('>i', length_bytes)[0]
                    
                    # Validate length (Max 64KB safety check)
                    if length <= 0 or length > 65536:
                        consecutive_errors += 1
                        if consecutive_errors >= max_consecutive_errors: break
                        continue
                    
                    # Read audio data
                    data = self._recv_exact(sock, length)
                    if not data: break
                    
                    consecutive_errors = 0
                    
                    # --- PROCESS ---
                    final_data = data
                    
                    if self.use_rnnoise and self.rnnoise:
                        try:
                            # RNNoise optimization handled inside denoiser.py
                            processed = self.rnnoise.process(data)
                            if processed: final_data = processed
                            else: continue
                        except Exception: pass

                    # --- GAIN & VISUALIZER ---
                    audio_array = np.frombuffer(final_data, dtype=np.int16)
                    
                    if self.current_gain != 1.0:
                        audio_array = np.clip(audio_array * self.current_gain, -32768, 32767).astype(np.int16)
                        final_data = audio_array.tobytes()

                    # Send RMS to UI (Throttle to avoid flooding socket)
                    if len(audio_array) > 0 and int(time.time() * 10) % 2 == 0:
                        rms = np.sqrt(np.mean(audio_array.astype(float)**2))
                        self.send_to_flutter({"type": "volume", "value": min(rms / 2000, 1.0)})
                    
                    stream.write(final_data)
                    
                except socket.timeout:
                    self.send_to_flutter({"type": "log", "message": "[!] Read timeout..."})
                    consecutive_errors += 1
                    if consecutive_errors >= max_consecutive_errors: break
                    continue

        except Exception as e:
            self.send_to_flutter({"type": "error", "message": str(e)})
        finally:
            if stream: 
                try:
                    stream.stop_stream()
                    stream.close()
                except: pass
            if sock: 
                try: sock.close()
                except: pass
            
            self.is_streaming = False
            self.send_to_flutter({"type": "status", "payload": "stopped"})
            self.send_to_flutter({"type": "volume", "value": 0.0})

    def cleanup(self):
        self.is_streaming = False
        try:
            self.p.terminate()
            self.server_socket.close()
        except: pass

if __name__ == "__main__":
    BackendServer().start()
