package com.vcx.receiver.core

/**
 * Pull-side interface of the playout chain. The playout thread pulls chunks of PCM from a FrameSource
 * and writes them to AudioTrack. Today the chain is JitterBuffer -> AudioTrack; drift control
 * (milestone 2) will slot a resampling FrameSource in between that wraps the JitterBuffer.
 */
interface FrameSource {
    /**
     * Fills [out] (interleaved stereo s16le) with the next chunk.
     * @return frames written, or 0 if there is nothing to play yet (caller plays silence).
     */
    fun read(out: ByteArray): Int

    /** Resampling ratio offset in ppm (0 when there is no drift control). */
    val ratePpm: Float get() = 0f
}
