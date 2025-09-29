package com.telemetrylab.telemetrylab

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.flatten
import kotlin.math.sqrt
import kotlin.random.Random

data class TelemetryUiState(
    val isRunning: Boolean = false,
    val computeLoad: Int = 2,
    val currentLatency: Long = 0,
    val avgLatency: Long = 0,
    val jankPercentage: Double = 0.0,
    val jankFrameCount: Int = 0,
    val framesProcessed: Int = 0,
    val isPowerSaveMode: Boolean = false,
    val processingLog: List<ProcessingLogEntry> = emptyList()
)

data class ProcessingLogEntry(
    val id: Long,
    val frameNumber: Int,
    val computeLoad: Int,
    val processingTime: Long,
    val resultMean: Double,
    val resultStd: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class ProcessingResult(
    val frameNumber: Int,
    val processingTime: Long,
    val mean: Double,
    val std: Double
)

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var processingJob: Job? = null
    private var frameNumber = 0
    private val latencyHistory = ConcurrentLinkedQueue<Long>()
    private val maxHistorySize = 100
    private val maxLogEntries = 50

    // Jank tracking
    private var totalFramesTracked = 0
    private var jankFramesTracked = 0

    // Processing channel for backpressure
    private val processingChannel = Channel<Int>(capacity = 10)

    init {
        updatePowerSaveState()
        startProcessingChannel()
    }

    private fun updatePowerSaveState() {
        viewModelScope.launch {
            while (true) {
                val isPowerSave = powerManager.isPowerSaveMode
                if (_uiState.value.isPowerSaveMode != isPowerSave) {
                    _uiState.value = _uiState.value.copy(isPowerSaveMode = isPowerSave)
                }
                delay(5000) // Check every 5 seconds
            }
        }
    }

    fun updateJankStats(isJank: Boolean) {
        totalFramesTracked++
        if (isJank) {
            jankFramesTracked++
        }

        val jankPercentage = if (totalFramesTracked > 0) {
            (jankFramesTracked.toDouble() / totalFramesTracked.toDouble()) * 100.0
        } else {
            0.0
        }

        _uiState.value = _uiState.value.copy(
            jankPercentage = jankPercentage,
            jankFrameCount = jankFramesTracked
        )
    }

    private fun startProcessingChannel() {
        viewModelScope.launch(Dispatchers.Default) {
            processingChannel.receiveAsFlow().collect { computeLoad ->
                val result = performComputation(computeLoad)
                withContext(Dispatchers.Main) {
                    updateUIWithResult(result)
                }
            }
        }
    }

    fun startProcessing() {
        if (_uiState.value.isRunning) return

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            framesProcessed = 0
        )
        frameNumber = 0
        latencyHistory.clear()
        totalFramesTracked = 0
        jankFramesTracked = 0

        startFrameGeneration()
    }

    fun stopProcessing() {
        _uiState.value = _uiState.value.copy(isRunning = false)
        processingJob?.cancel()
        processingJob = null
    }

    fun updateComputeLoad(load: Int) {
        _uiState.value = _uiState.value.copy(computeLoad = load)
    }

    private fun startFrameGeneration() {
        processingJob = viewModelScope.launch(Dispatchers.Default) {
            while (_uiState.value.isRunning) {
                val frameRate = if (_uiState.value.isPowerSaveMode) 10 else 20
                val frameInterval = 1000L / frameRate // ms

                frameNumber++

                // Send frame for processing with backpressure handling
                val computeLoad = getEffectiveComputeLoad()
                if (!processingChannel.trySend(computeLoad).isSuccess) {
                    // Channel is full, frame dropped (backpressure)
                    println("Frame $frameNumber dropped due to backpressure")
                }

                delay(frameInterval)
            }
        }
    }

    private fun getEffectiveComputeLoad(): Int {
        val baseLoad = _uiState.value.computeLoad
        return if (_uiState.value.isPowerSaveMode) {
            maxOf(1, baseLoad - 1)
        } else {
            baseLoad
        }
    }

    private suspend fun performComputation(computeLoad: Int): ProcessingResult {
        val startTime = System.currentTimeMillis()

        // Create 256x256 float array
        val size = 256
        val array = Array(size) { FloatArray(size) { Random.nextFloat() * 255f } }

        // 3x3 convolution kernel
        val kernel = arrayOf(
            floatArrayOf(1f, 2f, 1f),
            floatArrayOf(2f, 4f, 2f),
            floatArrayOf(1f, 2f, 1f)
        )

        // Perform N convolution passes
        var currentArray = array
        repeat(computeLoad) {
            currentArray = convolve2D(currentArray, kernel)
        }

        // ✅ FIX: Flatten safely and convert to Double
        val flatArray: List<Double> = currentArray.flatMap { row ->
            row.map { it.toDouble() }
        }

        val mean = flatArray.average()
        val variance = flatArray.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)

        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime

        return ProcessingResult(
            frameNumber = frameNumber,
            processingTime = processingTime,
            mean = mean,
            std = std
        )
    }


    private fun convolve2D(input: Array<FloatArray>, kernel: Array<FloatArray>): Array<FloatArray> {
        val height = input.size
        val width = input[0].size
        val kernelSize = kernel.size
        val kernelOffset = kernelSize / 2

        val output = Array(height) { FloatArray(width) }

        for (i in kernelOffset until height - kernelOffset) {
            for (j in kernelOffset until width - kernelOffset) {
                var sum = 0f
                for (ki in 0 until kernelSize) {
                    for (kj in 0 until kernelSize) {
                        sum += input[i + ki - kernelOffset][j + kj - kernelOffset] * kernel[ki][kj]
                    }
                }
                output[i][j] = sum / 16f // Normalize
            }
        }

        return output
    }

    private fun updateUIWithResult(result: ProcessingResult) {
        // Update latency history
        latencyHistory.add(result.processingTime)
        if (latencyHistory.size > maxHistorySize) {
            latencyHistory.poll()
        }

        val avgLatency = if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toLong()
        } else {
            0L
        }

        // Create log entry
        val logEntry = ProcessingLogEntry(
            id = System.currentTimeMillis(),
            frameNumber = result.frameNumber,
            computeLoad = getEffectiveComputeLoad(),
            processingTime = result.processingTime,
            resultMean = result.mean,
            resultStd = result.std
        )

        // Update processing log (keep only latest entries)
        val currentLog = _uiState.value.processingLog.toMutableList()
        currentLog.add(0, logEntry) // Add to beginning
        if (currentLog.size > maxLogEntries) {
            currentLog.removeAt(currentLog.size - 1)
        }

        // Update UI state
        _uiState.value = _uiState.value.copy(
            currentLatency = result.processingTime,
            avgLatency = avgLatency,
            framesProcessed = result.frameNumber,
            processingLog = currentLog
        )
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        processingChannel.close()
    }
}