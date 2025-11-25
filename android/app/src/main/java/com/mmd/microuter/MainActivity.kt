package com.mmd.microuter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var visualizer: WaveformView
    private lateinit var waveformReceiver: BroadcastReceiver
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Setup Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // 2. Setup Drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.open, R.string.close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // 3. Handle Menu Clicks
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        val btnToggle = findViewById<Button>(R.id.btnStart)
        val statusText = findViewById<TextView>(R.id.statusText)
        visualizer = findViewById(R.id.visualizer)

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopAudioService()
                btnToggle.text = "Start Server"
                statusText.text = "Status: Service Stopped"
                isServiceRunning = false
                visualizer.visibility = View.GONE
            } else {
                if (checkPermissions()) {
                    startAudioService()
                    btnToggle.text = "Stop Server"
                    statusText.text = "Status: Service Running (Background Safe)"
                    isServiceRunning = true
                    visualizer.visibility = View.VISIBLE
                }
            }
        }

        waveformReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val waveformData = intent?.getByteArrayExtra("waveform_data")
                if (waveformData != null) {
                    visualizer.updateData(waveformData)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(waveformReceiver, IntentFilter("com.mmd.microuter.WAVEFORM_DATA"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(waveformReceiver, IntentFilter("com.mmd.microuter.WAVEFORM_DATA"))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(waveformReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && isServiceRunning) {
            stopAudioService()
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
