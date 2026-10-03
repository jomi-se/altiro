package org.altiro.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_FRAME = 2
    const val MAX_SECONDS = 300
    const val MAX_FRAMES = SAMPLE_RATE * MAX_SECONDS

    fun header(frames: Long): ByteArray {
        require(frames in 0..MAX_FRAMES.toLong())
        val size = (frames * BYTES_PER_FRAME).toInt()
        return ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + size)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * BYTES_PER_FRAME)
                putShort(BYTES_PER_FRAME.toShort())
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(size)
            }.array()
    }
}
