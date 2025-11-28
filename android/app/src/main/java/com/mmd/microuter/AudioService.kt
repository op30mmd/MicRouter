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
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.mmd.microuter.utils.AppLogger
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

class AudioService : Service() {

    private var serverJob: Job? = null
    private var isServiceActive = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val CHANNEL_ID = "MicRouterChannel"

    // Hardware components with thread-safe access
    private var recorder: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var echo: AcousticEchoCanceler? = null
    private val recorderLock = ReentrantLock()
    
    // Track actual initialized sample rate
    private var actualSampleRate: Int = 48000

    // Throttling
    private var broadcastCounter = 0
    
    // Connection state
    private var isClientConnected = AtomicBoolean(false)
    
    // Reconnection throttle
    private var lastConnectionTime = 0L
    private val MIN_CONNECTION_INTERVAL_MS = 500L

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
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, AudioService::class.java).apply { action = "STOP" },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
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
                // Initialize microphone FIRST, before accepting any connections
                if (!initMicrophone()) {
                    AppLogger.e("AudioService", "Failed to init mic on startup. Stopping service.")
                    broadcastStatus("MIC_ERROR")
                    stopEverything()
                    return@launch
                }

                serverSocket = ServerSocket(port)
                serverSocket?.soTimeout = 1000 // 1 second timeout for accept()
                
                AppLogger.i("AudioService", "Server waiting on port $port")
                broadcastStatus("WAITING_FOR_PC")

                while (isServiceActive.get()) {
                    try {
                        val client = serverSocket?.accept()
                        
                        if (client != null) {
                            // Throttle rapid reconnections
                            val now = System.currentTimeMillis()
                            if (now - lastConnectionTime < MIN_CONNECTION_INTERVAL_MS) {
                                AppLogger.w("AudioService", "Connection too fast, throttling...")
                                delay(MIN_CONNECTION_INTERVAL_MS)
                            }
                            lastConnectionTime = now
                            
                            // Ensure mic is ready before handling client
                            recorderLock.withLock {
                                if (recorder == null || recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                    AppLogger.w("AudioService", "Mic not ready for new client. Reinitializing...")
                                    releaseMicInternal()
                                    if (!initMicrophoneInternal()) {
                                        AppLogger.e("AudioService", "Failed to reinit mic. Rejecting client.")
                                        client.close()
                                        continue
                                    }
                                }
                            }
                            
                            isClientConnected.set(true)
                            AppLogger.i("AudioService", "PC Connected from ${client.inetAddress}")
                            broadcastStatus("PC_CONNECTED")

                            handleClientConnection(client)

                            isClientConnected.set(false)
                            AppLogger.i("AudioService", "PC Disconnected.")
                            broadcastStatus("WAITING_FOR_PC")
                        }
                    } catch (e: SocketTimeoutException) {
                        // Normal timeout, just continue the loop
                        continue
                    } catch (e: SocketException) {
                        if (isServiceActive.get()) {
                            AppLogger.w("AudioService", "Socket exception: ${e.message}")
                        }
                    } catch (e: Exception) {
                        if (isServiceActive.get()) {
                            AppLogger.e("AudioService", "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("AudioService", "Critical Server Error", e)
                broadcastStatus("SERVER_ERROR")
            } finally {
                stopEverything()
            }
        }
    }

    private fun initMicrophone(): Boolean {
        return recorderLock.withLock {
            initMicrophoneInternal()
        }
    }

    private fun initMicrophoneInternal(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.e("AudioService", "No RECORD_AUDIO permission")
            return false
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val preferredSampleRate = prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000
        val useHwSuppressor = prefs.getBoolean("enable_hw_suppressor", true)

        val sampleRatesToTry = listOf(preferredSampleRate, 44100, 48000, 16000, 22050, 8000).distinct()
        val audioSourcesToTry = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (sampleRate in sampleRatesToTry) {
            for (audioSource in audioSourcesToTry) {
                if (tryInitRecorder(sampleRate, audioSource, useHwSuppressor)) {
                    actualSampleRate = sampleRate
                    AppLogger.i("AudioService", "Microphone initialized: rate=$sampleRate, source=$audioSource")
                    
                    val sessionIntent = Intent("com.mmd.microuter.AUDIO_SESSION_ID").apply {
                        setPackage(packageName)
                        putExtra("audio_session_id", recorder!!.audioSessionId)
                    }
                    sendBroadcast(sessionIntent)
                    
                    return true
                }
            }
        }

        AppLogger.e("AudioService", "Failed to initialize mic with any configuration")
        return false
    }

    private fun tryInitRecorder(sampleRate: Int, audioSource: Int, useHwSuppressor: Boolean): Boolean {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 4096)

        try {
            // Release any existing recorder first
            releaseMicInternal()
            
            recorder = AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.w("AudioService", "AudioRecord not initialized: rate=$sampleRate")
                recorder?.release()
                recorder = null
                return false
            }

            // Attach audio effects
            attachAudioEffects(useHwSuppressor)

            // Start recording
            recorder?.startRecording()
            
            // Wait a bit for hardware to stabilize
            Thread.sleep(50)

            if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AppLogger.e("AudioService", "Recording failed to start")
                releaseMicInternal()
                return false
            }

            // Test read
            val testBuffer = ByteArray(1024)
            val testRead = recorder?.read(testBuffer, 0, testBuffer.size) ?: -1
            if (testRead < 0) {
                AppLogger.e("AudioService", "Test read failed: $testRead")
                releaseMicInternal()
                return false
            }

            AppLogger.i("AudioService", "Mic started: rate=$sampleRate, buffer=$bufferSize")
            return true

        } catch (e: Exception) {
            AppLogger.e("AudioService", "tryInitRecorder failed: ${e.message}")
            releaseMicInternal()
            return false
        }
    }

    private fun attachAudioEffects(useHwSuppressor: Boolean) {
        val sessionId = recorder?.audioSessionId ?: return

        if (useHwSuppressor && NoiseSuppressor.isAvailable()) {
            try {
                suppressor = NoiseSuppressor.create(sessionId)
                suppressor?.enabled = true
                AppLogger.i("AudioService", "NoiseSuppressor enabled")
            } catch (e: Exception) {
                AppLogger.w("AudioService", "NoiseSuppressor failed: ${e.message}")
            }
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echo = AcousticEchoCanceler.create(sessionId)
                echo?.enabled = true
                AppLogger.i("AudioService", "AcousticEchoCanceler enabled")
            } catch (e: Exception) {
                AppLogger.w("AudioService", "AcousticEchoCanceler failed: ${e.message}")
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val noiseGateThreshold = prefs.getInt("noise_gate_threshold", 100).toDouble()

        val bufferSize = 2048
        val buffer = ByteArray(bufferSize)
        val waveformIntent = Intent("com.mmd.microuter.WAVEFORM_DATA").apply { 
            setPackage(packageName) 
        }

        try {
            // Configure socket for better reliability
            socket.tcpNoDelay = true
            socket.soTimeout = 5000 // 5 second read timeout
            socket.sendBufferSize = 65536
            
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // === HANDSHAKE PROTOCOL ===
            // 1. Send sample rate
            output.writeInt(actualSampleRate)
            output.flush()
            AppLogger.d("AudioService", "Sent sample rate: $actualSampleRate")
            
            // 2. Wait for client acknowledgment (with timeout)
            try {
                val ack = input.readInt()
                if (ack != actualSampleRate) {
                    AppLogger.e("AudioService", "Client sent wrong ack: $ack, expected: $actualSampleRate")
                    return
                }
                AppLogger.d("AudioService", "Client acknowledged sample rate")
            } catch (e: SocketTimeoutException) {
                AppLogger.e("AudioService", "Client didn't acknowledge sample rate (timeout)")
                return
            }
            
            // 3. Send ready signal
            output.writeInt(0x52454459) // "REDY" in hex
            output.flush()
            AppLogger.i("AudioService", "Handshake complete, starting audio stream")

            // === AUDIO STREAMING ===
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5
            
            while (isServiceActive.get() && !socket.isClosed && socket.isConnected) {
                val read: Int
                
                recorderLock.withLock {
                    if (recorder == null || recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        AppLogger.e("AudioService", "Recorder not in recording state")
                        return
                    }
                    read = recorder?.read(buffer, 0, buffer.size) ?: -1
                }

                when {
                    read > 0 -> {
                        consecutiveErrors = 0
                        
                        // Apply noise gate
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                sum += sample * sample
                            }
                        }
                        val rms = sqrt(sum / (read / 2))

                        if (rms < noiseGateThreshold) {
                            Arrays.fill(buffer, 0, read, 0.toByte())
                        }

                        // Write with length prefix for reliable framing
                        output.writeInt(read)
                        output.write(buffer, 0, read)
                        // Don't flush every packet for performance
                        
                        // Throttled visualizer broadcast
                        if (broadcastCounter++ % 5 == 0) {
                            output.flush() // Flush periodically
                            val validData = buffer.copyOfRange(0, read)
                            waveformIntent.putExtra("waveform_data", validData)
                            sendBroadcast(waveformIntent)
                        }
                    }
                    read == 0 -> {
                        // No data available, brief pause
                        Thread.sleep(1)
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        AppLogger.e("AudioService", "ERROR_INVALID_OPERATION")
                        consecutiveErrors++
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        AppLogger.e("AudioService", "ERROR_BAD_VALUE")
                        consecutiveErrors++
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        AppLogger.e("AudioService", "ERROR_DEAD_OBJECT - mic died")
                        recorderLock.withLock { releaseMicInternal() }
                        return
                    }
                    read == AudioRecord.ERROR -> {
                        AppLogger.e("AudioService", "Generic ERROR")
                        consecutiveErrors++
                    }
                    else -> {
                        AppLogger.e("AudioService", "Unknown read result: $read")
                        consecutiveErrors++
                    }
                }
                
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    AppLogger.e("AudioService", "Too many consecutive errors, dropping client")
                    return
                }
            }
        } catch (e: SocketException) {
            AppLogger.w("AudioService", "Socket error: ${e.message}")
        } catch (e: java.io.EOFException) {
            AppLogger.w("AudioService", "Client closed connection")
        } catch (e: Exception) {
            AppLogger.w("AudioService", "Client connection error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun releaseMic() {
        recorderLock.withLock {
            releaseMicInternal()
        }
    }

    private fun releaseMicInternal() {
        try {
            suppressor?.release()
            echo?.release()
            
            recorder?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        } catch (e: Exception) {
            AppLogger.e("AudioService", "Mic release error: ${e.message}")
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
        } catch (_: Exception) {}
        
        releaseMic()
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
