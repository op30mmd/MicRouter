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
    // AtomicBoolean prevents race conditions between UI stops and background loops
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
                        // Accept blocks until PC connects
                        val client = serverSocket?.accept()
                        if (client != null) {
                            Log.d("AudioService", "Client connected")

                            // BLOCKING CALL: Runs until PC disconnects
                            client.use { streamAudio(it) }

                            Log.d("AudioService", "Client disconnected. Cleaning up...")

                            // *** CRITICAL FIX FOR SAMSUNG DEVICES ***
                            // We MUST give the hardware time to fully release the Mic
                            // before trying to open it again for the next connection.
                            delay(1000)
                        }
                    } catch (e: SocketException) {
                        // Expected when serverSocket is closed manually
                        if (isStreaming.get()) Log.e("AudioService", "Socket error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Server loop crashed", e)
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

        // 1. USE MIC SOURCE (Fixes "Unsupported buffer mode")
        val audioSource = MediaRecorder.AudioSource.MIC
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // 2. LARGE BUFFER (Fixes Underruns/Blocking)
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 4

        var recorder: AudioRecord? = null

        try {
            recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

            // Hardware Noise Suppressor
            if (useHwSuppressor && NoiseSuppressor.isAvailable()) {
                try {
                    suppressor = NoiseSuppressor.create(recorder.audioSessionId)
                    suppressor?.enabled = true
                    Log.i("AudioService", "NoiseSuppressor ON")
                } catch (e: Exception) { Log.e("AudioService", "NS Failed: ${e.message}") }
            }

            // Echo Canceler
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echo = AcousticEchoCanceler.create(recorder.audioSessionId)
                    echo?.enabled = true
                } catch (e: Exception) { Log.e("AudioService", "AEC Failed: ${e.message}") }
            }

            // Setup Visualizer Intents
            val sessionIntent = Intent("com.mmd.microuter.AUDIO_SESSION_ID")
            sessionIntent.setPackage(packageName)
            sessionIntent.putExtra("audio_session_id", recorder.audioSessionId)
            sendBroadcast(sessionIntent)

            val waveformIntent = Intent("com.mmd.microuter.WAVEFORM_DATA")
            waveformIntent.setPackage(packageName)

            val buffer = ByteArray(bufferSize)
            val output = DataOutputStream(socket.getOutputStream())

            // Handshake
            output.writeInt(sampleRate)

            recorder.startRecording()

            // 3. STREAMING LOOP
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

                    // Software Noise Gate
                    if (rms < noiseGateThreshold) {
                        Arrays.fill(buffer, 0, read, 0.toByte())
                    }

                    // Send to PC
                    output.write(buffer, 0, read)

                    // Send to Visualizer (Fixing the glitchy graph)
                    // We only send the VALID bytes, not the full garbage buffer
                    val validData = buffer.copyOfRange(0, read)
                    waveformIntent.putExtra("waveform_data", validData)
                    sendBroadcast(waveformIntent)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Streaming interrupted: ${e.message}")
        } finally {
            // 4. CLEANUP
            try {
                if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop()
                }
                recorder?.release()
                suppressor?.release()
                echo?.release()
            } catch (e: Exception) {
                Log.e("AudioService", "Cleanup failed", e)
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
