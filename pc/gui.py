import customtkinter as ctk
import pyaudio
import socket
import threading
import subprocess
import sys
import time
import struct
import math
import audioop

# Appearance Settings
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class MicRouterApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        # Config
        self.HOST = "127.0.0.1"
        self.PORT = 6000
        self.SAMPLE_RATE = 44100
        self.CHUNK = 1024
        self.running = False
        self.audio_thread = None
        self.p = pyaudio.PyAudio()
        self.device_map = {}

        # Window Setup
        self.title("MicRouter PC Client")
        self.geometry("400x550")
        self.resizable(False, False)

        # UI Layout
        self.create_widgets()
        self.scan_audio_devices()

    def create_widgets(self):
        # Title
        self.label_title = ctk.CTkLabel(self, text="Android Mic Router", font=("Roboto Medium", 20))
        self.label_title.pack(pady=20)

        # Status
        self.status_frame = ctk.CTkFrame(self)
        self.status_frame.pack(pady=10, padx=20, fill="x")
        
        self.label_status = ctk.CTkLabel(self.status_frame, text="Status: Idle", text_color="gray")
        self.label_status.pack(pady=10)

        # Device Selection
        self.label_device = ctk.CTkLabel(self, text="Output Device (Virtual Cable/Speakers):")
        self.label_device.pack(pady=(20, 5))

        self.combo_devices = ctk.CTkComboBox(self, values=["Scanning..."], width=300)
        self.combo_devices.pack(pady=5)

        # Volume Slider
        self.label_vol = ctk.CTkLabel(self, text="Digital Gain (Volume Boost):")
        self.label_vol.pack(pady=(20, 5))
        
        self.slider_vol = ctk.CTkSlider(self, from_=1.0, to=5.0, number_of_steps=40)
        self.slider_vol.set(1.0)
        self.slider_vol.pack(pady=5)

        # Start/Stop Button
        self.btn_toggle = ctk.CTkButton(self, text="START RECEIVING", height=50, command=self.toggle_stream)
        self.btn_toggle.pack(pady=40, padx=20, fill="x")

        # Log
        self.textbox_log = ctk.CTkTextbox(self, height=100)
        self.textbox_log.pack(pady=10, padx=20, fill="both", expand=True)
        self.textbox_log.insert("0.0", "Ready. Connect phone via USB.\n")

    def log(self, message):
        self.textbox_log.insert("end", message + "\n")
        self.textbox_log.see("end")

    def scan_audio_devices(self):
        self.device_map = {}
        device_names = []
        info = self.p.get_host_api_info_by_index(0)
        numdevices = info.get("deviceCount")

        default_index = 0
        cable_index = 0

        for i in range(0, numdevices):
            device_info = self.p.get_device_info_by_host_api_device_index(0, i)
            if device_info.get("maxOutputChannels") > 0:
                name = device_info.get("name")
                self.device_map[name] = i
                device_names.append(name)
                
                if "CABLE Input" in name:
                    cable_index = i
                    default_index = i # Prefer cable

        self.combo_devices.configure(values=device_names)
        
        # Select Cable if found, else default
        if device_names:
            target_name = [k for k, v in self.device_map.items() if v == default_index][0]
            self.combo_devices.set(target_name)

    def setup_adb(self):
        self.log("[*] Initializing ADB...")
        try:
            subprocess.run(["adb", "forward", f"tcp:{self.PORT}", f"tcp:{self.PORT}"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.log("[*] ADB Forwarding active.")
            return True
        except Exception as e:
            self.log("[!] ADB Error. Is phone connected?")
            self.log(str(e))
            return False

    def toggle_stream(self):
        if not self.running:
            self.start_stream()
        else:
            self.stop_stream()

    def start_stream(self):
        if not self.setup_adb():
            return

        self.running = True
        self.btn_toggle.configure(text="STOP", fg_color="#db3e39", hover_color="#a62e2a")
        self.label_status.configure(text="Status: Connecting...", text_color="orange")
        
        self.audio_thread = threading.Thread(target=self.stream_process)
        self.audio_thread.start()

    def stop_stream(self):
        self.running = False
        self.btn_toggle.configure(text="START RECEIVING", fg_color="#1f6aa5", hover_color="#144870")
        self.label_status.configure(text="Status: Stopped", text_color="gray")
        self.log("[*] Stopped.")

    def stream_process(self):
        selected_device_name = self.combo_devices.get()
        device_index = self.device_map.get(selected_device_name)
        
        try:
            stream = self.p.open(format=pyaudio.paInt16,
                                 channels=1,
                                 rate=self.SAMPLE_RATE,
                                 output=True,
                                 output_device_index=device_index,
                                 frames_per_buffer=self.CHUNK)

            self.log(f"[*] Audio Output: {selected_device_name}")
            
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(5) # Do not hang forever
            
            try:
                s.connect((self.HOST, self.PORT))
                self.label_status.configure(text="Status: CONNECTED / STREAMING", text_color="#2cc985")
                self.log("[*] Connected to Phone!")
                s.settimeout(None)
                
                while self.running:
                    data = s.recv(self.CHUNK)
                    if not data: break
                    
                    # Apply volume gain
                    gain = self.slider_vol.get()
                    if gain != 1.0:
                        try:
                            # Mul multiplies the signal by the gain factor
                            # 2 = width in bytes (16-bit)
                            data = audioop.mul(data, 2, gain)
                        except Exception:
                            pass
                    
                    stream.write(data)
                    
            except Exception as e:
                self.log(f"[!] Connection Lost: {e}")
            finally:
                s.close()
                stream.stop_stream()
                stream.close()

        except Exception as e:
            self.log(f"[!] Audio Device Error: {e}")
        
        if self.running: # If crashed but wasn not stopped manually
            self.after(0, self.stop_stream)

    def on_closing(self):
        self.running = False
        self.p.terminate()
        self.destroy()

if __name__ == "__main__":
    app = MicRouterApp()
    app.protocol("WM_DELETE_WINDOW", app.on_closing)
    app.mainloop()