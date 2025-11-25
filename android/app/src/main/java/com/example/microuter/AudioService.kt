package com.example.microuter

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
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class AudioService : Service() {

    private var serverJob: Job? = null
    private var isStreaming = false
    private var serverSocket: ServerSocket? = null
    private val PORT = 6000
    private var sampleRate = 44100
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

        if (!isStreaming) {
            sampleRate = intent?.getIntExtra("sample_rate", 44100) ?: 44100
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
            .setContentText("Streaming microphone to PC at $sampleRate Hz...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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
                CHANNEL_ID,
                "MicRouter Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startStreaming() {
        isStreaming = true
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d("AudioService", "Server started on port $PORT")
                
                while (isStreaming) {
                    serverSocket?.accept()?.use { client ->
                        Log.d("AudioService", "Client connected")
                        streamAudio(client)
                    }
                }
            } catch (e: SocketException) {
                if (!isStreaming || serverSocket?.isClosed == true) {
                    Log.i("AudioService", "Server stopped normally.")
                } else {
                    Log.e("AudioService", "Server crashed unexpectedly", e)
                }
            } catch (e: Exception) {
                Log.e("AudioService", "General server error", e)
            } finally {
                stopStreaming()
            }
        }
    }

    private fun streamAudio(socket: Socket) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        // 1. CHANGE SOURCE TO MIC
        // VOICE_COMMUNICATION is causing the "Unsupported buffer mode" crash.
        // MIC is universally supported and stable.
        val audioSource = MediaRecorder.AudioSource.MIC
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // 2. INCREASE BUFFER SIZE
        // 48kHz needs a bigger buffer to avoid "underrun" or "blocking" errors.
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 4 // Safer than * 2

        val recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

        if (NoiseSuppressor.isAvailable()) {
            try {
                suppressor = NoiseSuppressor.create(recorder.audioSessionId)
                if (suppressor != null) {
                    suppressor?.enabled = true
                    Log.i("AudioService", "NoiseSuppressor Enabled!")
                } else {
                    Log.e("AudioService", "NoiseSuppressor creation failed.")
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Failed to init NoiseSuppressor: ${e.message}")
            }
        }

        if (AcousticEchoCanceler.isAvailable()) {
             try {
                echo = AcousticEchoCanceler.create(recorder.audioSessionId)
                if (echo != null) {
                    echo?.enabled = true
                    Log.i("AudioService", "AcousticEchoCanceler Enabled!")
                } else {
                    Log.e("AudioService", "AcousticEchoCanceler creation failed.")
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Failed to init AcousticEchoCanceler: ${e.message}")
            }
        }

        val intent = Intent("com.example.microuter.AUDIO_SESSION_ID")
        intent.setPackage(packageName)
        intent.putExtra("audio_session_id", recorder.audioSessionId)
        sendBroadcast(intent)

        val waveformIntent = Intent("com.example.microuter.WAVEFORM_DATA")
        waveformIntent.setPackage(packageName)

        val buffer = ByteArray(bufferSize)

        try {
            val output = DataOutputStream(socket.getOutputStream())

            output.writeInt(sampleRate)

            recorder.startRecording()
            
            while (isStreaming && socket.isConnected) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    waveformIntent.putExtra("waveform_data", buffer)
                    sendBroadcast(waveformIntent)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Stream error", e)
        } finally {
            try {
                recorder.stop()
                recorder.release()
                suppressor?.release()
                echo?.release()
            } catch (e: Exception) {}
        }
    }

    private fun stopStreaming() {
        isStreaming = false
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
