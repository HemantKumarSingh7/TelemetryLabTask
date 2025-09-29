package com.telemetrylab.telemetrylab.compute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class ComputeResult(
    val frameId: Long,
    val latencyMs: Long,
    val mean: Float,
    val std: Float
)

class ComputeEngine {

    private val kernel = arrayOf(
        floatArrayOf(1f, 2f, 1f),
        floatArrayOf(2f, 4f, 2f),
        floatArrayOf(1f, 2f, 1f)
    )

    suspend fun processFrame(frameId: Long, computeLoad: Int): ComputeResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // Create 256x256 float array
        var data = createRandomArray(256, 256)

        // Run convolution N times based on compute load
        repeat(computeLoad) {
            data = convolve2D(data, kernel)
        }

        // Calculate statistics
        val mean = calculateMean(data)
        val std = calculateStd(data, mean)

        val latency = System.currentTimeMillis() - startTime

        ComputeResult(
            frameId = frameId,
            latencyMs = latency,
            mean = mean,
            std = std
        )
    }

    private fun createRandomArray(rows: Int, cols: Int): Array<FloatArray> {
        return Array(rows) { FloatArray(cols) { (Math.random() * 255).toFloat() } }
    }

    private fun convolve2D(input: Array<FloatArray>, kernel: Array<FloatArray>): Array<FloatArray> {
        val rows = input.size
        val cols = input[0].size
        val kSize = kernel.size
        val kHalf = kSize / 2
        val output = Array(rows) { FloatArray(cols) }

        for (i in kHalf until rows - kHalf) {
            for (j in kHalf until cols - kHalf) {
                var sum = 0f
                for (ki in 0 until kSize) {
                    for (kj in 0 until kSize) {
                        sum += input[i + ki - kHalf][j + kj - kHalf] * kernel[ki][kj]
                    }
                }
                output[i][j] = sum / 16f // Normalize
            }
        }

        return output
    }

    private fun calculateMean(data: Array<FloatArray>): Float {
        var sum = 0.0
        var count = 0
        for (row in data) {
            for (value in row) {
                sum += value
                count++
            }
        }
        return (sum / count).toFloat()
    }

    private fun calculateStd(data: Array<FloatArray>, mean: Float): Float {
        var sumSquaredDiff = 0.0
        var count = 0
        for (row in data) {
            for (value in row) {
                val diff = value - mean
                sumSquaredDiff += diff * diff
                count++
            }
        }
        return sqrt(sumSquaredDiff / count).toFloat()
    }
}