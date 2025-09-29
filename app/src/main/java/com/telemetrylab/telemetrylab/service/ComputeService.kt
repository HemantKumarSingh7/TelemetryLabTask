package com.telemetrylab.telemetrylab.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.telemetrylab.telemetrylab.MainActivity
import com.telemetrylab.telemetrylab.R
import com.telemetrylab.telemetrylab.TelemetryLabApplication
import com.telemetrylab.telemetrylab.compute.ComputeEngine
import com.telemetrylab.telemetrylab.compute.ComputeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class ComputeService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val computeEngine = ComputeEngine()

    private var computeJob: Job? = null
    private var frameCounter = 0L

    private val _computeResults = MutableSharedFlow<ComputeResult>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val computeResults = _computeResults.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _powerSaveMode = MutableStateFlow(false)
    val powerSaveMode: StateFlow<Boolean> = _powerSaveMode.asStateFlow()

    private var currentComputeLoad = 2
    private lateinit var powerManager: PowerManager

    // ✅ FIXED: BroadcastReceiver for continuous power save monitoring
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val isPowerSave = powerManager.isPowerSaveMode
                    _powerSaveMode.value = isPowerSave

                    // Restart compute with adjusted parameters if running
                    if (_isRunning.value) {
                        restartCompute()
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ComputeService = this@ComputeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Initial check
        _powerSaveMode.value = powerManager.isPowerSaveMode

        // ✅ Register BroadcastReceiver for power save changes
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerSaveReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        // ✅ REQUIRED: Specify FGS type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    fun startCompute(computeLoad: Int) {
        if (_isRunning.value) return

        currentComputeLoad = computeLoad
        _isRunning.value = true
        frameCounter = 0L

        launchComputeJob()
    }

    private fun launchComputeJob() {
        computeJob?.cancel()

        val targetFrequency = if (_powerSaveMode.value) 10 else 20
        val adjustedLoad = if (_powerSaveMode.value) maxOf(1, currentComputeLoad - 1) else currentComputeLoad
        val frameIntervalMs = 1000L / targetFrequency

        computeJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                val frameStart = System.currentTimeMillis()

                try {
                    val result = computeEngine.processFrame(frameCounter++, adjustedLoad)
                    _computeResults.emit(result)

                    updateNotification(result)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val elapsed = System.currentTimeMillis() - frameStart
                val delayTime = (frameIntervalMs - elapsed).coerceAtLeast(0)

                if (delayTime > 0) {
                    delay(delayTime)
                }
            }
        }
    }

    private fun restartCompute() {
        if (_isRunning.value) {
            launchComputeJob()
        }
    }

    fun stopCompute() {
        _isRunning.value = false
        computeJob?.cancel()
        computeJob = null
        updateNotification(null)
    }

    fun updateComputeLoad(load: Int) {
        currentComputeLoad = load
        if (_isRunning.value) {
            stopCompute()
            startCompute(load)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TelemetryLabApplication.CHANNEL_ID)
            .setContentTitle("Telemetry Lab")
            .setContentText("Edge compute processing idle")
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(result: ComputeResult?) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val content = if (result != null) {
            "Frame ${result.frameId} • ${result.latencyMs}ms"
        } else {
            "Edge compute processing idle"
        }

        val notification = NotificationCompat.Builder(this, TelemetryLabApplication.CHANNEL_ID)
            .setContentTitle("Telemetry Lab")
            .setContentText(content)
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopCompute()
        unregisterReceiver(powerSaveReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}