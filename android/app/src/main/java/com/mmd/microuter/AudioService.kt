package com.mmd.microuter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.mmd.microuter.utils.AppLogger // Assuming you kept the logger
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioService : Service() {

    private var serverJob: Job? = null
    private var isServiceActive = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val CHANNEL_ID = "MicRouterChannel"

    // Hardware components
    private var recorder: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var echo: AcousticEchoCanceler? = null

    // Throttling
    private var broadcastCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopEverything()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isServiceActive.get()) {
            startForegroundService()
            startServerLoop()
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
            .setContentText("Microphone is ready. Waiting for PC...")
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

    private fun startServerLoop() {
        isServiceActive.set(true)
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AudioService)
            val port = prefs.getString("server_port", "6000")?.toIntOrNull() ?: 6000

            try {
                serverSocket = ServerSocket(port)
                AppLogger.i("AudioService", "Server waiting on port $port")
                broadcastStatus("WAITING_FOR_PC")

                while (isServiceActive.get()) {
                    // 1. Ensure Mic is Ready (Auto-Recovery)
                    if (recorder == null) {
                        AppLogger.w("AudioService", "Mic not ready. Initializing...")
                        if (!initMicrophone()) {
                            AppLogger.e("AudioService", "Failed to init mic. Stopping service.")
                            stopEverything()
                            break
                        }
                    }

                    try {
                        // 2. Accept Client
                        val client = serverSocket?.accept()
                        if (client != null) {
                            AppLogger.i("AudioService", "PC Connected!")
                            broadcastStatus("PC_CONNECTED")
                            
                            handleClientConnection(client)
                            
                            AppLogger.i("AudioService", "PC Disconnected.")
                            broadcastStatus("WAITING_FOR_PC")
                        }
                    } catch (e: Exception) {
                        if (isServiceActive.get()) AppLogger.e("AudioService", "Socket accept failed", e)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("AudioService", "Critical Server Error", e)
            } finally {
                stopEverything()
            }
        }
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val preferredSampleRate = prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000
        val useHwSuppressor = prefs.getBoolean("enable_hw_suppressor", true)

        // Sample rates to try in order of preference
        val sampleRatesToTry = listOf(preferredSampleRate, 44100, 48000, 16000, 22050, 8000)
            .distinct()

        // Audio sources to try (some devices work better with different sources)
        val audioSourcesToTry = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (sampleRate in sampleRatesToTry) {
            for (audioSource in audioSourcesToTry) {
                if (tryInitRecorder(sampleRate, audioSource, useHwSuppressor)) {
                    // Store actual sample rate for client communication
                    prefs.edit().putInt("actual_sample_rate", sampleRate).apply()
                    AppLogger.i("AudioService", "Microphone initialized: rate=$sampleRate, source=$audioSource")
                    return true
                }
            }
        }

        AppLogger.e("AudioService", "Failed to initialize mic with any configuration")
        return false
    }

    private fun tryInitRecorder(sampleRate: Int, audioSource: Int, useHwSuppressor: Boolean): Boolean {
        // Validate buffer size first
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            AppLogger.w("AudioService", "Invalid buffer size for rate: $sampleRate")
            return false
        }

        // Use conservative buffer multiplier (power of 2 works better on some devices)
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        try {
            // METHOD 1: Try legacy constructor first (more compatible)
            recorder = try {
                AudioRecord(
                    audioSource,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                AppLogger.w("AudioService", "Legacy constructor failed: ${e.message}")
                null
            }

            // METHOD 2: If legacy fails, try Builder API on M+
            if ((recorder == null || recorder?.state != AudioRecord.STATE_INITIALIZED)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                recorder?.release()
                recorder = tryBuilderInit(sampleRate, audioSource, bufferSize)
            }

            // Validate initialization
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.w("AudioService", "AudioRecord not initialized: rate=$sampleRate, source=$audioSource")
                recorder?.release()
                recorder = null
                return false
            }

            // Attach audio effects BEFORE starting recording
            attachAudioEffects(useHwSuppressor)

            // Start recording with error handling
            try {
                recorder?.startRecording()
            } catch (e: Exception) {
                AppLogger.e("AudioService", "startRecording failed: ${e.message}")
                releaseMic()
                return false
            }

            // Verify recording actually started
            Thread.sleep(100) // Small delay for hardware to stabilize

            if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AppLogger.e("AudioService", "Recording state invalid after start")
                releaseMic()
                return false
            }

            // Test read to confirm it works
            val testBuffer = ByteArray(bufferSize)
            val testRead = recorder?.read(testBuffer, 0, testBuffer.size) ?: -1
            if (testRead < 0) {
                AppLogger.e("AudioService", "Test read failed: $testRead")
                releaseMic()
                return false
            }

            // Broadcast session ID
            val sessionIntent = Intent("com.mmd.microuter.AUDIO_SESSION_ID").apply {
                setPackage(packageName)
                putExtra("audio_session_id", recorder!!.audioSessionId)
            }
            sendBroadcast(sessionIntent)

            AppLogger.i("AudioService", "Mic started: rate=$sampleRate, buffer=$bufferSize")
            return true

        } catch (e: Exception) {
            AppLogger.e("AudioService", "tryInitRecorder failed: ${e.message}")
            releaseMic()
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun tryBuilderInit(sampleRate: Int, audioSource: Int, bufferSize: Int): AudioRecord? {
        return try {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val builder = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)

            // On Android 10+, try setting performance mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Use reflection to avoid crashes on devices that don't support this
                    val setPerformanceModeMethod = AudioRecord.Builder::class.java.getMethod(
                        "setPerformanceMode", Int::class.javaPrimitiveType
                    )
                    // PERFORMANCE_MODE_LOW_LATENCY = 1
                    setPerformanceModeMethod.invoke(builder, 1)
                } catch (e: Exception) {
                    AppLogger.w("AudioService", "setPerformanceMode not available")
                }
            }

            builder.build()
        } catch (e: Exception) {
            AppLogger.w("AudioService", "Builder init failed: ${e.message}")
            null
        }
    }

    private fun attachAudioEffects(useHwSuppressor: Boolean) {
        val sessionId = recorder?.audioSessionId ?: return

        // Attach NoiseSuppressor
        if (useHwSuppressor && NoiseSuppressor.isAvailable()) {
            try {
                suppressor = NoiseSuppressor.create(sessionId)
                if (suppressor != null) {
                    suppressor?.enabled = true
                    AppLogger.i("AudioService", "NoiseSuppressor enabled")
                }
            } catch (e: Exception) {
                AppLogger.w("AudioService", "NoiseSuppressor failed: ${e.message}")
                suppressor = null
            }
        }

        // Attach AcousticEchoCanceler
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echo = AcousticEchoCanceler.create(sessionId)
                if (echo != null) {
                    echo?.enabled = true
                    AppLogger.i("AudioService", "AcousticEchoCanceler enabled")
                }
            } catch (e: Exception) {
                AppLogger.w("AudioService", "AcousticEchoCanceler failed: ${e.message}")
                echo = null
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Use the ACTUAL sample rate that was successfully initialized
        val sampleRate = prefs.getInt("actual_sample_rate",
            prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000)

        val noiseGateThreshold = prefs.getInt("noise_gate_threshold", 100).toDouble()

        // Smaller buffer for lower latency
        val bufferSize = 2048
        val buffer = ByteArray(bufferSize)
        val waveformIntent = Intent("com.mmd.microuter.WAVEFORM_DATA").apply {
            setPackage(packageName)
        }

        try {
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(sampleRate)

            while (isServiceActive.get() && socket.isConnected && !socket.isClosed) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1

                when {
                    read > 0 -> {
                        // Process and send audio...
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                sum += sample * sample
                            }
                        }
                        val rms = sqrt(sum / (read / 2))

                        if (rms < noiseGateThreshold) {
                            Arrays.fill(buffer, 0, read, 0.toByte())
                        }

                        output.write(buffer, 0, read)

                        if (broadcastCounter++ % 3 == 0) {
                            val validData = buffer.copyOfRange(0, read)
                            waveformIntent.putExtra("waveform_data", validData)
                            sendBroadcast(waveformIntent)
                        }
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        AppLogger.e("AudioService", "ERROR_INVALID_OPERATION - reinitializing")
                        releaseMic()
                        break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        AppLogger.e("AudioService", "ERROR_BAD_VALUE - reinitializing")
                        releaseMic()
                        break
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        AppLogger.e("AudioService", "ERROR_DEAD_OBJECT - reinitializing")
                        releaseMic()
                        break
                    }
                    read < 0 -> {
                        AppLogger.e("AudioService", "Unknown read error: $read")
                        releaseMic()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("AudioService", "Client connection dropped: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun releaseMic() {
        try {
            if (recorder != null) {
                if (recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder!!.stop()
                }
                recorder!!.release()
            }
            suppressor?.release()
            echo?.release()
        } catch (e: Exception) {
            AppLogger.e("AudioService", "Mic Release Error: ${e.message}")
        } finally {
            recorder = null
            suppressor = null
            echo = null
        }
    }

    private fun stopEverything() {
        isServiceActive.set(false)
        broadcastStatus("STOPPED")
        try {
            serverSocket?.close()
            releaseMic()
        } catch (e: Exception) {
            AppLogger.e("AudioService", "Cleanup error", e)
        }
        serverJob?.cancel()
    }
    
    private fun broadcastStatus(status: String) {
        val intent = Intent("com.mmd.microuter.STATUS")
        intent.setPackage(packageName)
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}