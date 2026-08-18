package com.labpano.gpxclient

import kotlin.math.ceil

/**
 * Adds regular 250 ms backup samples without hiding real smartphone GPS outages.
 * Every genuine fix is preserved; interpolation is allowed only across gaps <= 5 seconds.
 */
class PhoneGpsPointDensifier(
    private val requestedIntervalMillis: Long = 250L,
    private val maxInterpolationGapMillis: Long = 5_000L,
    private val maximumOutputPoints: Int = 2_000_000
) {
    data class Result(
        val points: List<PhoneGpsPoint>,
        val interpolatedPointCount: Int,
        val effectiveIntervalMillis: Long,
        val interpolationLimited: Boolean
    )

    fun densify(points: List<PhoneGpsPoint>): Result {
        val ordered = points.sortedBy { it.time }
        if (ordered.size < 2 || requestedIntervalMillis <= 0L) {
            return Result(ordered, 0, requestedIntervalMillis.coerceAtLeast(0L), false)
        }
        if (ordered.size >= maximumOutputPoints) {
            return Result(ordered, 0, Long.MAX_VALUE, true)
        }

        val desired = countInterpolated(ordered, requestedIntervalMillis)
        val available = maximumOutputPoints - ordered.size
        val limited = desired > available
        val interval = if (!limited || desired == 0L) requestedIntervalMillis else chooseInterval(ordered, available)
        val result = ArrayList<PhoneGpsPoint>((ordered.size.toLong() + countInterpolated(ordered, interval))
            .coerceAtMost(maximumOutputPoints.toLong()).toInt())
        result += ordered.first()
        var interpolated = 0

        ordered.zipWithNext().forEach { (first, second) ->
            val duration = second.time - first.time
            if (duration > interval && duration <= maxInterpolationGapMillis) {
                var timestamp = first.time + interval
                while (timestamp < second.time && result.size < maximumOutputPoints - 1) {
                    val fraction = (timestamp - first.time).toDouble() / duration.toDouble()
                    result += interpolate(first, second, timestamp, fraction)
                    interpolated++
                    timestamp += interval
                }
            }
            result += second
        }
        return Result(result, interpolated, interval, limited)
    }

    private fun chooseInterval(points: List<PhoneGpsPoint>, available: Int): Long {
        if (available <= 0) return Long.MAX_VALUE
        var low = requestedIntervalMillis
        var high = maxInterpolationGapMillis.coerceAtLeast(low)
        while (countInterpolated(points, high) > available && high < Long.MAX_VALUE / 2L) {
            high = (high * 2L).coerceAtMost(Long.MAX_VALUE / 2L)
        }
        while (low < high) {
            val mid = low + (high - low) / 2L
            if (countInterpolated(points, mid) <= available) high = mid else low = mid + 1L
        }
        return low
    }

    private fun countInterpolated(points: List<PhoneGpsPoint>, interval: Long): Long {
        if (interval <= 0L || interval == Long.MAX_VALUE) return 0L
        var count = 0L
        points.zipWithNext().forEach { (first, second) ->
            val duration = second.time - first.time
            if (duration > interval && duration <= maxInterpolationGapMillis) {
                count += ceil(duration.toDouble() / interval.toDouble()).toLong() - 1L
            }
        }
        return count
    }

    private fun interpolate(first: PhoneGpsPoint, second: PhoneGpsPoint, time: Long, fraction: Double): PhoneGpsPoint =
        PhoneGpsPoint(
            time = time,
            latitude = lerp(first.latitude, second.latitude, fraction),
            longitude = interpolateLongitude(first.longitude, second.longitude, fraction),
            altitude = lerpNullable(first.altitude, second.altitude, fraction),
            accuracyMeters = lerpNullable(first.accuracyMeters?.toDouble(), second.accuracyMeters?.toDouble(), fraction)?.toFloat(),
            provider = "interpolated",
            speedMetersPerSecond = lerpNullable(first.speedMetersPerSecond?.toDouble(), second.speedMetersPerSecond?.toDouble(), fraction)?.toFloat(),
            bearingDegrees = interpolateBearing(first.bearingDegrees, second.bearingDegrees, fraction)
        )

    private fun interpolateLongitude(start: Double, end: Double, fraction: Double): Double {
        var delta = end - start
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        var value = start + delta * fraction
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    private fun interpolateBearing(start: Float?, end: Float?, fraction: Double): Float? {
        if (start == null && end == null) return null
        if (start == null) return end
        if (end == null) return start
        var delta = end.toDouble() - start.toDouble()
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        var value = start + delta * fraction
        while (value >= 360.0) value -= 360.0
        while (value < 0.0) value += 360.0
        return value.toFloat()
    }

    private fun lerp(start: Double, end: Double, fraction: Double): Double = start + (end - start) * fraction
    private fun lerpNullable(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> lerp(start, end, fraction)
        start != null -> start
        else -> end
    }
}
