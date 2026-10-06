package org.altiro.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test
    fun `header describes exact PCM frame count at all supported durations`() {
        for (seconds in listOf(0, 10, 30, 120, 300)) {
            val header = Wav.header(seconds.toLong() * Wav.SAMPLE_RATE)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44, header.size)
            assertEquals(36 + seconds * 32000, buffer.getInt(4))
            assertEquals(16000, buffer.getInt(24))
            assertEquals(32000, buffer.getInt(28))
            assertEquals(seconds * 32000, buffer.getInt(40))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized recordings cannot overflow header or evade limit`() {
        Wav.header(Wav.MAX_FRAMES.toLong() + 1)
    }
}
