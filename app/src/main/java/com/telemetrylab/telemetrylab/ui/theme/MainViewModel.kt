package com.telemetrylab.telemetrylab.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telemetrylab.telemetrylab.compute.ComputeResult
import com.telemetrylab.telemetrylab.service.ComputeService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TelemetryState(
    val isRunning: Boolean = false,
    val computeLoad: Int = 2,
    val currentLatency: Long = 0L,
    val averageLatency: Float = 0f,
    val jankPercentage: Float = 0f,
    val jankFrameCount: Int = 0,
    val frameCount: Long = 0,
    val powerSaveMode: Boolean = false
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TelemetryState())
    val uiState: StateFlow<TelemetryState> = _uiState.asStateFlow()

    private val latencyHistory = mutableListOf<Long>()
    private val maxHistorySize = 100

    private var computeService: ComputeService? = null

    fun setComputeService(service: ComputeService) {
        computeService = service

        viewModelScope.launch {
            service.isRunning.collect { running ->
                _uiState.update { it.copy(isRunning = running) }
            }
        }

        viewModelScope.launch {
            service.powerSaveMode.collect { powerSave ->
                _uiState.update { it.copy(powerSaveMode = powerSave) }
            }
        }

        viewModelScope.launch {
            service.computeResults.collect { result ->
                processComputeResult(result)
            }
        }
    }

    fun toggleCompute() {
        val service = computeService ?: return

        if (_uiState.value.isRunning) {
            service.stopCompute()
            latencyHistory.clear()
        } else {
            service.startCompute(_uiState.value.computeLoad)
        }
    }

    fun updateComputeLoad(load: Int) {
        _uiState.update { it.copy(computeLoad = load) }
        computeService?.updateComputeLoad(load)
    }

    private fun processComputeResult(result: ComputeResult) {
        latencyHistory.add(result.latencyMs)
        if (latencyHistory.size > maxHistorySize) {
            latencyHistory.removeAt(0)
        }

        val avgLatency = if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toFloat()
        } else {
            0f
        }

        _uiState.update {
            it.copy(
                currentLatency = result.latencyMs,
                averageLatency = avgLatency,
                frameCount = result.frameId
            )
        }
    }

    fun updateJankStats(jankPercentage: Float, jankCount: Int) {
        _uiState.update {
            it.copy(
                jankPercentage = jankPercentage,
                jankFrameCount = jankCount
            )
        }
    }
}