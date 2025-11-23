import pyaudio
import socket
import subprocess
import threading
import time
import numpy as np
import struct

class Streamer:
    def __init__(self, config, log_callback=None, status_callback=None):
        self.config = config
        self.p = pyaudio.PyAudio()
        self.running = False
        self.log = log_callback if log_callback else print
        self.status_callback = status_callback if status_callback else print

    def setup_adb(self):
        self.log("[*] Initializing ADB...")
        try:
            subprocess.run(["adb", "forward", f"tcp:{self.config.PORT}", f"tcp:{self.config.PORT}"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.log("[*] ADB Forwarding active.")
            return True
        except Exception as e:
            self.log("[!] ADB Error. Is phone connected?")
            self.log(str(e))
            return False

    def get_audio_devices(self):
        device_map = {}
        device_names = []
        info = self.p.get_host_api_info_by_index(0)
        num_devices = info.get("deviceCount")

        for i in range(num_devices):
            device_info = self.p.get_device_info_by_host_api_device_index(0, i)
            if device_info.get("maxOutputChannels") > 0:
                name = device_info.get("name")
                device_map[name] = i
                device_names.append(name)

        return device_names, device_map

    def start(self, device_index, gain_callback):
        if not self.setup_adb():
            self.status_callback("failed")
            return

        self.running = True
        self.thread = threading.Thread(target=self.stream_process, args=(device_index, gain_callback))
        self.thread.start()

    def stop(self):
        self.running = False
        self.status_callback("stopped")

    def cleanup(self):
        self.p.terminate()

    def stream_process(self, device_index, gain_callback):
        s = None
        stream = None
        try:
            self.status_callback("connecting")
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.log(f"[*] Connecting to {self.config.HOST}:{self.config.PORT}...")
            s.connect((self.config.HOST, self.config.PORT))
            self.log("[*] Connected to Phone!")

            # 1. Receive the Header (4 bytes) safely
            raw_header = s.recv(4)
            if len(raw_header) < 4:
                self.log("[!] Error: Connection closed prematurely by the phone.")
                self.log("[!] Cause: The selected Sample Rate (e.g., 44100Hz) is likely unsupported.")
                self.log("[!] Fix: Try selecting a different rate (e.g., 48000Hz or 16000Hz) in the app.")
                self.running = False
                self.status_callback("failed")
                return

            # 2. Unpack using '>I' (Big Endian Unsigned Int) to match Java's DataOutputStream
            sample_rate = struct.unpack('>I', raw_header)[0]
            self.log(f"[*] Sample Rate received: {sample_rate} Hz")

            # 3. Setup Audio Player
            stream = self.p.open(format=pyaudio.paInt16,
                                 channels=1,
                                 rate=sample_rate,
                                 output=True,
                                 output_device_index=device_index,
                                 frames_per_buffer=self.config.CHUNK)

            self.status_callback("streaming")
            self.log("[*] Streaming audio...")

            while self.running:
                data = s.recv(self.config.CHUNK)
                if not data:
                    self.log("[*] Stream ended by phone.")
                    break

                gain = gain_callback()
                if gain != 1.0:
                    # Apply digital gain if needed
                    audio_data = np.frombuffer(data, dtype=np.int16)
                    audio_data = np.clip(audio_data * gain, -32768, 32767)
                    data = audio_data.astype(np.int16).tobytes()

                stream.write(data)

        except ConnectionRefusedError:
            self.log("[!] Connection Refused. Is the App server running?")
            self.log("[!] Tip: Ensure ADB forwarding is active (`adb forward tcp:6000 tcp:6000`)")
        except socket.timeout:
            self.log("[!] Connection timed out. Check the phone and ADB connection.")
        except Exception as e:
            self.log(f"[!] An error occurred: {e}")
        finally:
            if s:
                s.close()
            if stream:
                stream.stop_stream()
                stream.close()

            self.log("[*] Stream stopped.")
            # Only update status if it was an unexpected stop
            if self.running:
                self.running = False
                self.status_callback("failed")
