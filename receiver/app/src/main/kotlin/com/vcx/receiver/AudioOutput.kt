package com.vcx.receiver

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.vcx.receiver.core.Vcx

/**
 * Low-latency AudioTrack, 48 kHz stereo s16, MODE_STREAM.
 *
 * Buffer sizing: build with 2x getMinBufferSize (or more if needed to hold the target), then
 * setBufferSizeInFrames(burst * trackBursts), where burst = PROPERTY_OUTPUT_FRAMES_PER_BUFFER.
 */
class AudioOutput(context: Context, trackBursts: Int) {

    val burstFrames: Int
    val nativeSampleRate: Int
    val minBufferBytes: Int
    val requestedFrames: Int
    val track: AudioTrack

    init {
        val am = context.getSystemService(AudioManager::class.java)
        burstFrames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: 192
        nativeSampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        minBufferBytes = AudioTrack.getMinBufferSize(
            Vcx.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        requestedFrames = burstFrames * trackBursts
        val buildBytes = maxOf(2 * minBufferBytes, requestedFrames * Vcx.BYTES_PER_FRAME)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Vcx.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(buildBytes)
            .build()
        track.setBufferSizeInFrames(requestedFrames)
    }

    /** Actual buffer size after setBufferSizeInFrames (the device may round or clamp it). */
    val bufferFrames: Int get() = track.bufferSizeInFrames
    val capacityFrames: Int get() = track.bufferCapacityInFrames
    val isFastTrack: Boolean get() = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

    fun play() = track.play()

    /** Blocking write of [bytes] bytes; returns false if the track stopped accepting data. */
    fun writeFully(buf: ByteArray, bytes: Int): Boolean {
        var off = 0
        while (off < bytes) {
            val n = track.write(buf, off, bytes - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    fun underrunCount(): Int = track.underrunCount

    fun release() {
        try { track.pause(); track.flush() } catch (_: IllegalStateException) {}
        track.release()
    }
}
