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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class AudioService : Service() {

    private var serverJob: Job? = null
    private var isStreaming = false
    private var serverSocket: ServerSocket? = null
    private val PORT = 6000
    private var sampleRate = 44100
    private val CHANNEL_ID = "MicRouterChannel"

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
                    val client = serverSocket?.accept()
                    Log.d("AudioService", "Client connected")
                    client?.let { streamAudio(it) }
                    client?.close()
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Server error", e)
            }
        }
    }

    private fun streamAudio(socket: Socket) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val buffer = ByteArray(bufferSize)
        
        try {
            val output = DataOutputStream(socket.getOutputStream())

            // Send the sample rate first
            output.writeInt(sampleRate)

            recorder.startRecording()
            
            while (isStreaming && socket.isConnected) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    output.write(buffer, 0, read)

                    // Broadcast the audio data for the visualizer
                    val intent = Intent("com.example.microuter.AUDIO_DATA")
                    intent.putExtra("audio_data", buffer)
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Stream error", e)
        } finally {
            try {
                recorder.stop()
                recorder.release()
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
