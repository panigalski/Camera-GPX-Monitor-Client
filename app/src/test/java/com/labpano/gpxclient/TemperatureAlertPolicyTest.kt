package com.labpano.gpxclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureAlertPolicyTest {
    @Test
    fun alertsWhenTemperatureCrossesDefaultThreshold() {
        val decision = TemperatureAlertPolicy.evaluate(73.1, 73.0, true, 0L, 1_000L)
        assertTrue(decision.shouldAlert)
        assertFalse(decision.armed)
    }

    @Test
    fun doesNotAlertAtThreshold() {
        val decision = TemperatureAlertPolicy.evaluate(73.0, 73.0, true, 0L, 1_000L)
        assertFalse(decision.shouldAlert)
        assertTrue(decision.armed)
    }

    @Test
    fun rearmsThreeDegreesBelowThreshold() {
        val decision = TemperatureAlertPolicy.evaluate(70.0, 73.0, false, 5_000L, 6_000L)
        assertFalse(decision.shouldAlert)
        assertTrue(decision.armed)
    }

    @Test
    fun cooldownPreventsImmediateRepeatAfterRearm() {
        val decision = TemperatureAlertPolicy.evaluate(
            temperatureC = 74.0,
            thresholdC = 73.0,
            armed = true,
            lastAlertAt = 1_000L,
            now = 2_000L
        )
        assertFalse(decision.shouldAlert)
        assertTrue(decision.armed)
    }
    @Test
    fun explicitRearmAfterUnmuteAllowsWarningWhileTemperatureRemainsHigh() {
        val stillLatched = TemperatureAlertPolicy.evaluate(
            temperatureC = 80.0,
            thresholdC = 73.0,
            armed = false,
            lastAlertAt = 9_000L,
            now = 10_000L
        )
        assertFalse(stillLatched.shouldAlert)

        // App Sounds unmute resets the persisted alert state to armed=true / lastAlertAt=0.
        val afterUnmuteReset = TemperatureAlertPolicy.evaluate(
            temperatureC = 80.0,
            thresholdC = 73.0,
            armed = true,
            lastAlertAt = 0L,
            now = 10_000L
        )
        assertTrue(afterUnmuteReset.shouldAlert)
        assertFalse(afterUnmuteReset.armed)
    }

}
