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
        val sampleRate = prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000
        val useHwSuppressor = prefs.getBoolean("enable_hw_suppressor", true)

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = minBufferSize * 8

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            }

            if (useHwSuppressor && NoiseSuppressor.isAvailable()) {
                suppressor = NoiseSuppressor.create(recorder!!.audioSessionId)
                suppressor?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(recorder!!.audioSessionId)
                echo?.enabled = true
            }

            recorder?.startRecording()
            AppLogger.i("AudioService", "Microphone Started")
            
            // Broadcast Session ID for Visualizer
            val sessionIntent = Intent("com.mmd.microuter.AUDIO_SESSION_ID")
            sessionIntent.setPackage(packageName)
            sessionIntent.putExtra("audio_session_id", recorder!!.audioSessionId)
            sendBroadcast(sessionIntent)

            return true
        } catch (e: Exception) {
            AppLogger.e("AudioService", "Mic Init Failed: ${e.message}")
            releaseMic() // Ensure cleanup on partial failure
            return false
        }
    }

    private fun handleClientConnection(socket: Socket) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val sampleRate = prefs.getString("sample_rate", "48000")?.toIntOrNull() ?: 48000
        val noiseGateThreshold = prefs.getInt("noise_gate_threshold", 100).toDouble()
        
        val bufferSize = 4096 
        val buffer = ByteArray(bufferSize)
        val waveformIntent = Intent("com.mmd.microuter.WAVEFORM_DATA").apply { setPackage(packageName) }

        try {
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(sampleRate)

            while (isServiceActive.get() && socket.isConnected && !socket.isClosed) {
                // Read from the ALREADY OPEN recorder
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1

                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / (read / 2))

                    if (rms < noiseGateThreshold) {
                        Arrays.fill(buffer, 0, read, 0.toByte())
                    }

                    output.write(buffer, 0, read)

                    // Throttled Visualizer Broadcast (Every 3rd frame)
                    if (broadcastCounter++ % 3 == 0) {
                        val validData = buffer.copyOfRange(0, read)
                        waveformIntent.putExtra("waveform_data", validData)
                        sendBroadcast(waveformIntent)
                    }
                } else if (read < 0) {
                    AppLogger.e("AudioService", "AudioRecord read error: $read. Triggering restart...")
                    releaseMic() // Kill the mic so the outer loop restarts it
                    break 
                }
            }
        } catch (e: Exception) {
            AppLogger.w("AudioService", "Client connection dropped: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
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