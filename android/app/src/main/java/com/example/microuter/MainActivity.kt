package com.example.microuter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnToggle = findViewById<Button>(R.id.btnToggle)
        val statusText = findViewById<TextView>(R.id.statusText)

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopAudioService()
                btnToggle.text = "Start Server"
                statusText.text = "Status: Service Stopped"
                isServiceRunning = false
            } else {
                if (checkPermissions()) {
                    startAudioService()
                    btnToggle.text = "Stop Server"
                    statusText.text = "Status: Service Running (Background Safe)"
                    isServiceRunning = true
                }
            }
        }
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioService::class.java)
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
