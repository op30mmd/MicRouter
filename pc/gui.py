import customtkinter as ctk
import time
from core import Streamer
import config

# Appearance Settings
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class MicRouterApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        # Config
        self.streamer = Streamer(config, self.log, self.update_status)
        self.device_map = {}

        # Window Setup
        self.title("MicRouter PC Client")
        self.geometry("400x550")
        self.resizable(False, False)

        # UI Layout
        self.create_widgets()
        self.scan_audio_devices()

    def create_widgets(self):
        self.label_title = ctk.CTkLabel(self, text="Android Mic Router", font=("Roboto Medium", 20))
        self.label_title.pack(pady=20)

        self.status_frame = ctk.CTkFrame(self)
        self.status_frame.pack(pady=10, padx=20, fill="x")
        
        self.label_status = ctk.CTkLabel(self.status_frame, text="Status: Idle", text_color="gray")
        self.label_status.pack(pady=10)

        self.label_device = ctk.CTkLabel(self, text="Output Device (Virtual Cable/Speakers):")
        self.label_device.pack(pady=(20, 5))

        self.combo_devices = ctk.CTkComboBox(self, values=["Scanning..."], width=300)
        self.combo_devices.pack(pady=5)

        self.label_vol = ctk.CTkLabel(self, text="Digital Gain (Volume Boost):")
        self.label_vol.pack(pady=(20, 5))
        
        self.slider_vol = ctk.CTkSlider(self, from_=1.0, to=5.0, number_of_steps=40)
        self.slider_vol.set(1.0)
        self.slider_vol.pack(pady=5)

        self.btn_toggle = ctk.CTkButton(self, text="START RECEIVING", height=50, command=self.toggle_stream)
        self.btn_toggle.pack(pady=40, padx=20, fill="x")

        self.textbox_log = ctk.CTkTextbox(self, height=100)
        self.textbox_log.pack(pady=10, padx=20, fill="both", expand=True)
        self.textbox_log.insert("0.0", "Ready. Connect phone via USB.\n")

    def log(self, message):
        self.textbox_log.insert("end", message + "\n")
        self.textbox_log.see("end")

    def scan_audio_devices(self):
        device_names, self.device_map = self.streamer.get_audio_devices()
        self.combo_devices.configure(values=device_names)
        
        if device_names:
            cable_name = next((name for name in device_names if "CABLE Input" in name), device_names[0])
            self.combo_devices.set(cable_name)

    def toggle_stream(self):
        if not self.streamer.running:
            self.start_stream()
        else:
            self.stop_stream()

    def start_stream(self):
        selected_device = self.combo_devices.get()
        device_index = self.device_map.get(selected_device)
        self.streamer.start(device_index, self.slider_vol.get)

    def stop_stream(self):
        self.streamer.stop()

    def update_status(self, status):
        if status == "connecting":
            self.btn_toggle.configure(text="STOP", fg_color="#db3e39", hover_color="#a62e2a")
            self.label_status.configure(text="Status: Connecting...", text_color="orange")
        elif status == "streaming":
            self.label_status.configure(text="Status: STREAMING", text_color="#2cc985")
        elif status == "stopped" or status == "failed":
            self.btn_toggle.configure(text="START RECEIVING", fg_color="#1f6aa5", hover_color="#144870")
            self.label_status.configure(text="Status: Stopped", text_color="gray")

    def on_closing(self):
        self.streamer.stop()
        self.streamer.cleanup()
        self.destroy()

if __name__ == "__main__":
    app = MicRouterApp()
    app.protocol("WM_DELETE_WINDOW", app.on_closing)
    app.mainloop()
