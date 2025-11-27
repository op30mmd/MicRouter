package com.mmd.microuter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioService : Service() {

    private var serverJob: Job? = null
    private var isStreaming = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val CHANNEL_ID = "MicRouterChannel"

    private var suppressor: NoiseSuppressor? = null
    private var echo: AcousticEchoCanceler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopStreaming()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isStreaming.get()) {
            startForegroundService()
            startStreaming()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MicRouter Active")
            .setContentText("Listening for PC connection...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                PendingIntent.getService(this, 0, Intent(this, AudioService::class.java).apply { action = "STOP" }, PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "MicRouter Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun startStreaming() {
        isStreaming.set(true)
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AudioService)
            val port = prefs.getString("server_port", "6000")?.toIntOrNull() ?: 6000

            try {
                serverSocket = ServerSocket(port)
                Log.d("AudioService", "Server started on port $port")

                while (isStreaming.get()) {
                    try {
                        val client = serverSocket?.accept()
                        if (client != null) {
                            Log.d("AudioService", "Client connected")

                            // Give previous hardware lock a moment to release fully
                            delay(500)

                            client.use { streamAudio(it) }

                            Log.d("AudioService", "Client disconnected")
                        }
                    } catch (e: SocketException) {
                        if (isStreaming.get()) Log.e("AudioService", "Socket error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Server crashed", e)
            } finally {
                stopStreaming()
            }
        }
    }

    private fun streamAudio(socket: Socket) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val sampleRate = prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000
        val useHwSuppressor = prefs.getBoolean("enable_hw_suppressor", true)
        val noiseGateThreshold = prefs.getInt("noise_gate_threshold", 100).toDouble()

        val audioSource = MediaRecorder.AudioSource.MIC
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 4

        var recorder: AudioRecord? = null
        val output = DataOutputStream(socket.getOutputStream())

        try {
            // --- 1. ROBUST HARDWARE INITIALIZATION (RETRY LOGIC) ---
            var initAttempts = 0
            var initialized = false

            while (initAttempts < 3 && !initialized) {
                try {
                    recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.startRecording()
                        initialized = true
                    } else {
                        throw Exception("AudioRecord state uninitialized")
                    }
                } catch (e: Exception) {
                    Log.w("AudioService", "Mic init failed (Attempt ${initAttempts + 1}/3): ${e.message}")
                    recorder?.release()
                    recorder = null
                    initAttempts++
                    Thread.sleep(500) // Wait for hardware to reset
                }
            }

            if (!initialized || recorder == null) {
                Log.e("AudioService", "Critical: Failed to initialize mic after 3 attempts.")
                return // Exit cleanly, closing socket
            }

            // --- 2. EFFECTS SETUP ---
            if (useHwSuppressor && NoiseSuppressor.isAvailable()) {
                try {
                    suppressor = NoiseSuppressor.create(recorder.audioSessionId)
                    suppressor?.enabled = true
                } catch (e: Exception) { Log.e("AudioService", "NS Error: ${e.message}") }
            }

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echo = AcousticEchoCanceler.create(recorder.audioSessionId)
                    echo?.enabled = true
                } catch (e: Exception) { Log.e("AudioService", "AEC Error: ${e.message}") }
            }

            // --- 3. UI HANDSHAKE ---
            val sessionIntent = Intent("com.mmd.microuter.AUDIO_SESSION_ID")
            sessionIntent.setPackage(packageName)
            sessionIntent.putExtra("audio_session_id", recorder.audioSessionId)
            sendBroadcast(sessionIntent)

            val waveformIntent = Intent("com.mmd.microuter.WAVEFORM_DATA")
            waveformIntent.setPackage(packageName)

            // Send Header to PC (Big Endian)
            output.writeInt(sampleRate)

            val buffer = ByteArray(bufferSize)

            // --- 4. STREAMING LOOP ---
            while (isStreaming.get() && socket.isConnected && !socket.isClosed) {
                val read = recorder.read(buffer, 0, buffer.size)

                if (read > 0) {
                    // RMS Calculation
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / (read / 2))

                    // Noise Gate
                    if (rms < noiseGateThreshold) {
                        Arrays.fill(buffer, 0, read, 0.toByte())
                    }

                    // Write to PC (This throws Exception if PC disconnects, breaking the loop)
                    output.write(buffer, 0, read)

                    // Visualizer
                    val validData = buffer.copyOfRange(0, read)
                    waveformIntent.putExtra("waveform_data", validData)
                    sendBroadcast(waveformIntent)
                } else if (read < 0) {
                    Log.w("AudioService", "AudioRecord read error: $read")
                    Thread.sleep(10) // Prevent tight error loop
                }
            }
        } catch (e: Exception) {
            // This is normal when the PC disconnects or Stop is pressed
            Log.i("AudioService", "Streaming ended: ${e.message}")
        } finally {
            // --- 5. CLEANUP ---
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder?.stop()
                }
                recorder?.release()
                recorder = null // Help GC

                suppressor?.release()
                suppressor = null

                echo?.release()
                echo = null
            } catch (e: Exception) {
                Log.e("AudioService", "Cleanup error", e)
            }
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverJob?.cancel()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
