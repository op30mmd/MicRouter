import ctypes
import os
import numpy as np
import platform

class RNNoise:
    def __init__(self):
        self.lib = self._load_library()
        
        # Initialize RNNoise State
        # rnnoise_create(model) -> returns state pointer
        self.lib.rnnoise_create.restype = ctypes.c_void_p
        self.lib.rnnoise_create.argtypes = [ctypes.c_void_p]
        self.state = self.lib.rnnoise_create(None)
        
        # Constants (48kHz audio, 10ms frame = 480 samples)
        self.FRAME_SIZE = 480 
        self.buffer = np.array([], dtype=np.int16)

        # Define process function: (state, output_float*, input_float*)
        self.lib.rnnoise_process_frame.argtypes = [
            ctypes.c_void_p, 
            ctypes.POINTER(ctypes.c_float), 
            ctypes.POINTER(ctypes.c_float)
        ]
        self.lib.rnnoise_process_frame.restype = ctypes.c_float

    def _load_library(self):
        # Look for the DLL in the 'libs' folder next to this script
        base_path = os.path.dirname(os.path.abspath(__file__))
        lib_name = "rnnoise.dll" if platform.system() == "Windows" else "rnnoise.so"
        lib_path = os.path.join(base_path, "libs", lib_name)

        if not os.path.exists(lib_path):
            # Fallback for dev environment or different structure
            lib_path = os.path.join(base_path, lib_name)

        if not os.path.exists(lib_path):
            raise FileNotFoundError(f"Could not find {lib_name}. It will be built by GitHub Actions.")
            
        return ctypes.cdll.LoadLibrary(lib_path)

    def process(self, chunk_bytes):
        """
        Takes raw int16 bytes. Returns denoised raw int16 bytes.
        """
        # --- OPTIMIZATION: Fast Path for exact 10ms chunks ---
        if len(chunk_bytes) == 960: # 480 samples * 2 bytes
            # Convert bytes directly to float input buffer
            frame_float = np.frombuffer(chunk_bytes, dtype=np.int16).astype(np.float32)
            
            # Prepare C pointers
            in_ptr = frame_float.ctypes.data_as(ctypes.POINTER(ctypes.c_float))
            out_float = np.zeros(self.FRAME_SIZE, dtype=np.float32)
            out_ptr = out_float.ctypes.data_as(ctypes.POINTER(ctypes.c_float))

            # Process immediately
            self.lib.rnnoise_process_frame(self.state, out_ptr, in_ptr)

            return out_float.astype(np.int16).tobytes()
        
        # --- Existing Slow Path (Buffering) for other sizes ---
        new_data = np.frombuffer(chunk_bytes, dtype=np.int16)
        self.buffer = np.concatenate((self.buffer, new_data))
        
        output_bytes = bytearray()
        
        while len(self.buffer) >= self.FRAME_SIZE:
            frame = self.buffer[:self.FRAME_SIZE]
            self.buffer = self.buffer[self.FRAME_SIZE:]

            frame_float = frame.astype(np.float32)
            in_ptr = frame_float.ctypes.data_as(ctypes.POINTER(ctypes.c_float))
            out_float = np.zeros(self.FRAME_SIZE, dtype=np.float32)
            out_ptr = out_float.ctypes.data_as(ctypes.POINTER(ctypes.c_float))

            self.lib.rnnoise_process_frame(self.state, out_ptr, in_ptr)

            processed_frame = out_float.astype(np.int16)
            output_bytes.extend(processed_frame.tobytes())

        return bytes(output_bytes)

    def destroy(self):
        self.lib.rnnoise_destroy.argtypes = [ctypes.c_void_p]
        self.lib.rnnoise_destroy(self.state)
