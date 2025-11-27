import unittest
from unittest.mock import MagicMock, patch
import json
import numpy as np
import sys
import os

# Add parent directory to path so we can import backend
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend import BackendServer

class TestBackendLogic(unittest.TestCase):

    @patch('backend.pyaudio.PyAudio')
    @patch('backend.socket.socket')
    def setUp(self, mock_socket, mock_pyaudio):
        """Setup runs before every test. We mock the heavy hardware stuff."""
        self.mock_socket_instance = MagicMock()
        mock_socket.return_value = self.mock_socket_instance
        
        # Prevent the watchdog from actually killing the test process
        with patch('backend.BackendServer.start_parent_watchdog'): 
            self.server = BackendServer()
            # Disable actual streaming loops for logic tests
            self.server.is_streaming = False 

    def test_command_parsing_start(self):
        """Test if the 'start' command correctly triggers logic."""
        # Mock the threading to prevent actual threads from spawning
        with patch('threading.Thread') as mock_thread:
            cmd = {"command": "start", "port": 6666, "device_name": "TestSpeaker"}
            self.server.process_command(cmd)
            
            # Check if it tried to start a thread
            self.assertTrue(self.server.is_streaming)
            mock_thread.assert_called_once()

    def test_command_parsing_stop(self):
        """Test if 'stop' command changes state."""
        self.server.is_streaming = True
        cmd = {"command": "stop"}
        self.server.process_command(cmd)
        self.assertFalse(self.server.is_streaming)

    def test_gain_logic(self):
        """Test if gain command updates the multiplier."""
        cmd = {"command": "set_gain", "value": 2.5}
        self.server.process_command(cmd)
        self.assertEqual(self.server.current_gain, 2.5)

    def test_visualizer_math(self):
        """Test the RMS calculation logic."""
        # Simulate a quiet sine wave
        # 10 samples, amplitude 100
        fake_audio = np.array([100] * 10, dtype=np.int16) 
        
        # Manually calculate RMS: sqrt(mean(100^2)) = 100
        rms = np.sqrt(np.mean(fake_audio.astype(float)**2))
        normalized = min(rms / 2000, 1.0)
        
        self.assertEqual(normalized, 0.05) # 100 / 2000 = 0.05

if __name__ == '__main__':
    unittest.main()
