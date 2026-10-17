package com.vit.devicememorymonitor

class PressureAnalytics(private val maxSamples: Int = 60) {

    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()

    val sampleCount: Int get() = values.size

    fun addSample(timeMs: Long, percent: Double) {
        times.addLast(timeMs)
        values.addLast(percent)
        while (times.size > maxSamples) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    fun average(): Double = if (values.isEmpty()) 0.0 else values.average()

    // Least-squares slope in percentage points per minute
    fun slopePerMinute(): Double {
        val n = values.size
        if (n < 5) return 0.0
        val t0 = times[0]
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for (i in 0 until n) {
            val x = (times[i] - t0) / 60000.0
            val y = values[i]
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        val denom = n * sumXX - sumX * sumX
        if (Math.abs(denom) < 1e-9) return 0.0
        return (n * sumXY - sumX * sumY) / denom
    }

    fun etaMinutes(current: Double, threshold: Int): Double? {
        if (current >= threshold) return 0.0
        val slope = slopePerMinute()
        if (slope <= 0.05) return null
        return (threshold - current) / slope
    }

    fun label(): String {
        if (values.size < 5) return "Collecting data..."
        val slope = slopePerMinute()
        return when {
            slope >= 0.5 -> "Climbing"
            slope <= -0.5 -> "Falling"
            else -> "Stable"
        }
    }
}