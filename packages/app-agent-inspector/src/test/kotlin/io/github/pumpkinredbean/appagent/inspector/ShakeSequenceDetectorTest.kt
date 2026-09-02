package io.github.pumpkinredbean.appagent.inspector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeSequenceDetectorTest {
    @Test
    fun requiresTwoStrongSamplesInOneSequence() {
        val detector = ShakeSequenceDetector()

        assertFalse(detector.record(force = 2.6f, nowMillis = 0L))
        assertFalse(detector.record(force = 3.0f, nowMillis = 100L))
        assertTrue(detector.record(force = 3.1f, nowMillis = 350L))
    }

    @Test
    fun resetsSeparatedSamplesAndDebouncesRepeatedTriggers() {
        val detector = ShakeSequenceDetector()

        assertFalse(detector.record(force = 3.0f, nowMillis = 100L))
        assertFalse(detector.record(force = 3.0f, nowMillis = 700L))
        assertTrue(detector.record(force = 3.0f, nowMillis = 900L))
        assertFalse(detector.record(force = 3.0f, nowMillis = 1_000L))
        assertFalse(detector.record(force = 3.0f, nowMillis = 1_200L))
        assertFalse(detector.record(force = 3.0f, nowMillis = 2_200L))
        assertTrue(detector.record(force = 3.0f, nowMillis = 2_400L))
    }
}
