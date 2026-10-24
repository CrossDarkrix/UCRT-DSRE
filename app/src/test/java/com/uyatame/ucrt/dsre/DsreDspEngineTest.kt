package com.uyatame.ucrt.dsre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DsreDspEngineTest {
    private val params = DsreParameters(14, .45f, 48000, .98f, 1.10f, .04f)

    @Test fun outputShapeAndFiniteValuesArePreserved() {
        val n = 4093
        val left = FloatArray(n) { sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() * .5f }
        val right = FloatArray(n) { sin(2.0 * PI * 660.0 * it / 48000.0).toFloat() * .5f }
        val output = DsreDspEngine(48000, 2, params).process(arrayOf(left, right))
        assertEquals(2, output.size)
        assertEquals(n, output[0].size)
        assertEquals(n, output[1].size)
        assertTrue(output.all { channel -> channel.all { it.isFinite() && kotlin.math.abs(it) <= .98001f } })
    }

    @Test fun emptyChunkIsAccepted() {
        val output = DsreDspEngine(48000, 2, params).process(arrayOf(FloatArray(0), FloatArray(0)))
        assertEquals(0, output[0].size)
        assertEquals(0, output[1].size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedChannelsAreRejected() {
        DsreDspEngine(48000, 2, params).process(arrayOf(FloatArray(8), FloatArray(7)))
    }
}
