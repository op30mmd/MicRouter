import pyaudio
import socket
import subprocess
import threading
import time
import numpy as np

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
        try:
            stream = self.p.open(format=pyaudio.paInt16,
                                 channels=1,
                                 rate=self.config.SAMPLE_RATE,
                                 output=True,
                                 output_device_index=device_index,
                                 frames_per_buffer=self.config.CHUNK)
        except Exception as e:
            self.log(f"[!] Audio Device Error: {e}")
            self.running = False
            self.status_callback("failed")
            return

        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5)

        try:
            self.status_callback("connecting")
            s.connect((self.config.HOST, self.config.PORT))
            self.log("[*] Connected to Phone!")
            s.settimeout(None)
            self.status_callback("streaming")
        except Exception as e:
            self.log(f"[!] Connection failed: {e}")
            stream.close()
            self.running = False
            self.status_callback("failed")
            return

        while self.running:
            try:
                data = s.recv(self.config.CHUNK)
                if not data:
                    break

                gain = gain_callback()
                if gain != 1.0:
                    audio_data = np.frombuffer(data, dtype=np.int16)
                    audio_data = np.clip(audio_data * gain, -32768, 32767)
                    data = audio_data.astype(np.int16).tobytes()

                stream.write(data)
            except Exception as e:
                self.log(f"[!] Stream error: {e}")
                break

        s.close()
        stream.stop_stream()
        stream.close()
        self.log("[*] Stream stopped.")
        if self.running: # If it wasn't stopped manually
            self.running = False
            self.status_callback("stopped")
