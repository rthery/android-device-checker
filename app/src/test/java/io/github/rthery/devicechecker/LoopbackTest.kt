package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.interactive.LOOPBACK_MIN_SNR_DB
import io.github.rthery.devicechecker.interactive.loopbackSnrDb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class LoopbackTest {
    private val rate = 48_000
    private val random = Random(7)

    private fun signal(toneLevel: Double, noiseLevel: Double, toneHz: Double = 880.0) = List(rate * 2) { i ->
        val tone = toneLevel * sin(2 * PI * toneHz * i / rate)
        val noise = noiseLevel * (random.nextDouble() * 2 - 1)
        ((tone + noise) * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    @Test
    fun toneInNoiseIsHeard() {
        assertTrue(loopbackSnrDb(signal(toneLevel = 0.05, noiseLevel = 0.05)) > LOOPBACK_MIN_SNR_DB)
    }

    @Test
    fun noiseAloneIsNotHeard() {
        assertTrue(loopbackSnrDb(signal(toneLevel = 0.0, noiseLevel = 0.2)) < LOOPBACK_MIN_SNR_DB)
    }

    @Test
    fun silenceIsNotHeard() {
        assertTrue(loopbackSnrDb(List(rate) { 0 }) < LOOPBACK_MIN_SNR_DB)
    }

    @Test
    fun anotherFrequencyIsNotHeard() {
        assertTrue(loopbackSnrDb(signal(toneLevel = 0.5, noiseLevel = 0.01, toneHz = 440.0)) < LOOPBACK_MIN_SNR_DB)
    }
}
