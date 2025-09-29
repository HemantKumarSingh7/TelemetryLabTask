package com.telemetrylab.telemetrylab

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.metrics.performance.JankStats
import com.telemetrylab.telemetrylab.service.ComputeService
import com.telemetrylab.telemetrylab.ui.theme.MainViewModel
import com.telemetrylab.telemetrylab.ui.theme.TelemetryLabTheme
import com.telemetrylab.telemetrylab.ui.theme.TelemetryScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var computeService: ComputeService? = null
    private var jankStats: JankStats? = null

    // ✅ FIXED: Proper jank tracking
    private var totalFramesObserved = 0
    private var jankFramesObserved = 0
    private var jankTrackingStartTime = 0L
    private val JANK_WINDOW_MS = 30_000L // 30 seconds

    private val jankFrameListener = JankStats.OnFrameListener { frameData ->
        val currentTime = System.currentTimeMillis()

        // Reset window every 30 seconds
        if (jankTrackingStartTime == 0L || currentTime - jankTrackingStartTime > JANK_WINDOW_MS) {
            jankTrackingStartTime = currentTime
            totalFramesObserved = 0
            jankFramesObserved = 0
        }

        // Count all frames
        totalFramesObserved++

        // Count jank frames
        if (frameData.isJank) {
            jankFramesObserved++
        }

        // Calculate jank percentage
        val jankPercentage = if (totalFramesObserved > 0) {
            (jankFramesObserved.toFloat() / totalFramesObserved.toFloat()) * 100f
        } else {
            0f
        }

        viewModel.updateJankStats(jankPercentage, jankFramesObserved)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ComputeService.LocalBinder
            computeService = binder.getService()
            viewModel.setComputeService(binder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            computeService = null
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        setContent {
            TelemetryLabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TelemetryScreen(viewModel = viewModel)
                }
            }
        }

        window.decorView.post {
            setupJankStats()
        }
    }

    private fun setupJankStats() {
        jankStats = JankStats.createAndTrack(window, jankFrameListener)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startAndBindService()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, ComputeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        jankStats?.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jankStats?.isTrackingEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}