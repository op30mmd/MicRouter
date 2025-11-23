package com.example.microuter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gauravk.audiovisualizer.visualizer.BlastVisualizer

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var visualizer: BlastVisualizer
    private lateinit var audioDataReceiver: BroadcastReceiver
    private lateinit var spinnerSampleRate: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnToggle = findViewById<Button>(R.id.btnToggle)
        val statusText = findViewById<TextView>(R.id.statusText)
        visualizer = findViewById(R.id.visualizer)
        spinnerSampleRate = findViewById(R.id.spinnerSampleRate)

        ArrayAdapter.createFromResource(
            this,
            R.array.sample_rates,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSampleRate.adapter = adapter
        }

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopAudioService()
                btnToggle.text = "Start Server"
                statusText.text = "Status: Service Stopped"
                isServiceRunning = false
                visualizer.hide()
                spinnerSampleRate.isEnabled = true
            } else {
                if (checkPermissions()) {
                    startAudioService()
                    btnToggle.text = "Stop Server"
                    statusText.text = "Status: Service Running (Background Safe)"
                    isServiceRunning = true
                    visualizer.show()
                    spinnerSampleRate.isEnabled = false
                }
            }
        }

        audioDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val audioData = intent?.getByteArrayExtra("audio_data")
                if (audioData != null) {
                    visualizer.setRawAudioBytes(audioData)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(audioDataReceiver, IntentFilter("com.example.microuter.AUDIO_DATA"))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(audioDataReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && isServiceRunning) {
            stopAudioService()
        }
        visualizer.release()
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioService::class.java)
        val sampleRate = spinnerSampleRate.selectedItem.toString().split(" ")[0].toInt()
        intent.putExtra("sample_rate", sampleRate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAudioService() {
        val intent = Intent(this, AudioService::class.java)
        intent.action = "STOP"
        startService(intent)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        // Android 13+ requires notification permission
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
            return false
        }
        return true
    }
}
