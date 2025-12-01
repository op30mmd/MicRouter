package com.mmd.microuter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    private val _audioData = MutableStateFlow<ByteArray?>(null)
    val audioData = _audioData.asStateFlow()

    private val _serviceStatus = MutableStateFlow("STOPPED")
    val serviceStatus = _serviceStatus.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(prefs.getString("server_port", "6000") ?: "6000")
    val serverPort = _serverPort.asStateFlow()

    // Track actual sample rate if hardware fallback happened
    private val _displaySampleRate = MutableStateFlow(prefs.getString("sample_rate", "48000") ?: "48000")
    val displaySampleRate = _displaySampleRate.asStateFlow()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "server_port") {
            _serverPort.value = sharedPreferences.getString(key, "6000") ?: "6000"
        }
        if (key == "sample_rate" || key == "actual_sample_rate") {
            // Prefer showing the ACTUAL rate if the service set it, otherwise the requested one
            val actual = sharedPreferences.getInt("actual_sample_rate", 0)
            if (actual > 0) {
                _displaySampleRate.value = actual.toString()
            } else {
                _displaySampleRate.value = sharedPreferences.getString("sample_rate", "48000") ?: "48000"
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.mmd.microuter.WAVEFORM_DATA" -> {
                    _audioData.value = intent.getByteArrayExtra("waveform_data")
                }
                "com.mmd.microuter.STATUS" -> {
                    // Update UI State based on Service Broadcast
                    val status = intent.getStringExtra("status") ?: "STOPPED"
                    _serviceStatus.value = status

                    // Sync boolean flag
                    _isServiceRunning.value = (status != "STOPPED")

                    // Clear visualizer if stopped
                    if (status == "STOPPED") {
                        _audioData.value = null
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction("com.mmd.microuter.WAVEFORM_DATA")
            addAction("com.mmd.microuter.STATUS") // Listen for status updates
        }
        ContextCompat.registerReceiver(
            application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun toggleService(context: Context) {
        val intent = Intent(context, AudioService::class.java)
        if (_isServiceRunning.value) {
            intent.action = "STOP"
            context.startService(intent) // Send stop command
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }
}
