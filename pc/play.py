import socket
import pyaudio
import subprocess
import sys
import time

# Config
HOST = "127.0.0.1"
PORT = 6000
SAMPLE_RATE = 44100
CHANNELS = 1
FORMAT = pyaudio.paInt16
CHUNK = 1024

def setup_adb():
    print("[*] Setting up ADB port forwarding...")
    try:
        # Forward PC port 6000 to Android port 6000
        subprocess.run(["adb", "forward", f"tcp:{PORT}", f"tcp:{PORT}"], check=True)
        print(f"[*] Port {PORT} forwarded successfully.")
    except FileNotFoundError:
        print("[!] ADB not found. Ensure Android platform-tools are in your PATH.")
        sys.exit(1)
    except subprocess.CalledProcessError:
        print("[!] Failed to run adb forward. Is the device connected?")
        sys.exit(1)

def get_virtual_cable_index(p):
    """Finds the VB-Cable Input device index."""
    info = p.get_host_api_info_by_index(0)
    numdevices = info.get("deviceCount")
    
    candidate = None
    for i in range(0, numdevices):
        device_info = p.get_device_info_by_host_api_device_index(0, i)
        name = device_info.get("name")
        
        # Look for the VB-Cable Input
        if "CABLE Input" in name:
            print(f"[*] Found Virtual Cable: {name} (Index {i})")
            candidate = i
            break
            
    return candidate

def play_audio():
    p = pyaudio.PyAudio()
    
    # Auto-detect Virtual Cable
    device_index = get_virtual_cable_index(p)
    
    if device_index is None:
        print("[!] VB-Cable not found! Playing through default speakers.")
        print("    (To use as Mic, install VB-Cable: https://vb-audio.com/Cable/)")
    else:
        print("[*] Routing audio to Virtual Microphone...")

    stream = p.open(format=FORMAT,
                    channels=CHANNELS,
                    rate=SAMPLE_RATE,
                    output=True,
                    output_device_index=device_index,
                    frames_per_buffer=CHUNK)

    print(f"[*] Connecting to {HOST}:{PORT}...")
    
    while True:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.connect((HOST, PORT))
            print("[*] Connected! Streaming to Virtual Mic...")
            break
        except ConnectionRefusedError:
            print("[.] Connection refused. Waiting for App... (Ctrl+C to quit)")
            time.sleep(2)
        except KeyboardInterrupt:
            sys.exit(0)

    try:
        while True:
            data = s.recv(CHUNK)
            if not data:
                break
            stream.write(data)
    except KeyboardInterrupt:
        print("\n[*] Stopping...")
    finally:
        s.close()
        stream.stop_stream()
        stream.close()
        p.terminate()

if __name__ == "__main__":
    setup_adb()
    play_audio()

