package com.vcx.receiver

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.vcx.receiver.core.FrameSource
import com.vcx.receiver.core.JitterBuffer
import com.vcx.receiver.core.PacketCodec
import com.vcx.receiver.core.StatsFormat
import com.vcx.receiver.core.StatsSnapshot
import com.vcx.receiver.core.Vcx
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException

const val TAG = "VCX"

/**
 * Owns the sockets, jitter buffer, AudioTrack and the four threads (rx, playout, discovery, stats)
 * for one START. stop() tears everything down; a new START builds a new engine.
 */
class ReceiverEngine(
    private val context: Context,
    val targetMs: Int,
    val wifiMode: String,
    val trackBursts: Int,
) {
    @Volatile private var running = false
    @Volatile private var lastSource: SocketAddress? = null
    @Volatile var statusLine: String = "starting"
        private set
    @Volatile private var badPackets = 0L

    private val jitter = JitterBuffer(targetMs, log = { Log.i(TAG, it) })
    /** Playout chain head. Drift control (M2) will wrap [jitter] in a resampling FrameSource here. */
    private val source: FrameSource = jitter

    private lateinit var output: AudioOutput
    private lateinit var audioSocket: DatagramSocket
    private lateinit var discoverySocket: DatagramSocket
    private val threads = mutableListOf<Thread>()
    @Volatile private var underrunBase = 0

    fun start() {
        output = AudioOutput(context, trackBursts)
        try {
            audioSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 1 shl 20
                bind(InetSocketAddress(Vcx.AUDIO_PORT))
            }
            discoverySocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Vcx.DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            output.release()
            if (::audioSocket.isInitialized) audioSocket.close()
            throw e
        }

        Log.i(
            TAG,
            "VCX-START target_ms=$targetMs wifi_mode=$wifiMode track_frames=${output.bufferFrames} " +
                "burst_frames=${output.burstFrames} sample_rate=${Vcx.SAMPLE_RATE} track_bursts=$trackBursts " +
                "track_capacity=${output.capacityFrames} min_buffer_bytes=${output.minBufferBytes} " +
                "fast_track=${output.isFastTrack} native_rate=${output.nativeSampleRate} " +
                "rcvbuf=${audioSocket.receiveBufferSize}",
        )

        running = true
        threads += Thread(::playoutLoop, "vcx-playout")
        threads += Thread(::receiveLoop, "vcx-rx")
        threads += Thread(::discoveryLoop, "vcx-discovery")
        threads += Thread(::statsLoop, "vcx-stats")
        threads.forEach { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        audioSocket.close()
        discoverySocket.close()
        val playout = threads.first()
        playout.join(500)
        if (playout.isAlive) {
            try { output.track.stop() } catch (_: IllegalStateException) {}
            playout.join(500)
        }
        threads.forEach { it.interrupt(); it.join(500) }
        threads.clear()
        logStats() // final cumulative numbers
        output.release()
        Log.i(TAG, "VCX-STOP")
    }

    private fun setUrgentAudioPriority(name: String) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "$name: could not set URGENT_AUDIO priority: $e")
        }
        Log.i(TAG, "$name priority=${Process.getThreadPriority(Process.myTid())}")
    }

    private fun playoutLoop() {
        setUrgentAudioPriority("playout")
        val buf = ByteArray(Vcx.MAX_FRAMES_PER_PACKET * Vcx.BYTES_PER_FRAME)
        val silence = ByteArray(Vcx.DEFAULT_FRAMES * Vcx.BYTES_PER_FRAME)
        output.play()
        underrunBase = output.underrunCount()
        while (running) {
            val frames = source.read(buf)
            val ok = if (frames > 0) {
                output.writeFully(buf, frames * Vcx.BYTES_PER_FRAME)
            } else {
                // Not primed yet: keep the track fed with silence so the blocking write keeps pacing us.
                output.writeFully(silence, silence.size)
            }
            if (!ok && running) {
                Log.w(TAG, "AudioTrack write failed; playout thread exiting")
                break
            }
        }
    }

    private fun receiveLoop() {
        setUrgentAudioPriority("rx")
        val buf = ByteArray(65536)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            dp.setLength(buf.size)
            try {
                audioSocket.receive(dp)
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "rx: $e")
                continue
            }
            val pkt = PacketCodec.parseAudio(buf, 0, dp.length)
            if (pkt == null) {
                badPackets++
                continue
            }
            lastSource = dp.socketAddress
            jitter.push(pkt)
        }
    }

    private fun discoveryLoop() {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        var replySeq = 0
        while (running) {
            dp.setLength(buf.size)
            try {
                discoverySocket.receive(dp)
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "discovery: $e")
                continue
            }
            val pcName = PacketCodec.parseDiscover(buf, 0, dp.length) ?: continue
            val reply = PacketCodec.buildDiscoverReply(replySeq++, nowUs(), Vcx.AUDIO_PORT, Build.MODEL)
            try {
                discoverySocket.send(DatagramPacket(reply, reply.size, dp.socketAddress))
                Log.i(TAG, "VCX-DISCOVER from=${dp.socketAddress} pc_name=\"$pcName\" replied model=${Build.MODEL}")
            } catch (e: Exception) {
                Log.w(TAG, "discovery reply to ${dp.socketAddress} failed: $e")
            }
        }
    }

    private var statsSeq = 0
    private var lastExtra = ""

    private fun statsLoop() {
        var next = SystemClock.elapsedRealtime() + 1000
        while (running) {
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
            next += 1000
            if (!running) break
            logStats()
        }
    }

    private fun logStats() {
        val c = jitter.counters()
        val snap = StatsSnapshot(
            bufferUs = c.depthUs,
            targetUs = c.targetUs,
            targetMs = targetMs,
            received = c.received,
            lost = c.lost,
            recovered = c.recovered,
            concealed = c.concealed,
            late = c.late,
            underruns = (output.underrunCount() - underrunBase).toLong().coerceAtLeast(0),
            ratePpm = source.ratePpm,
            trackFrames = output.bufferFrames,
        )
        val line = StatsFormat.logLine(snap)
        Log.i(TAG, line)
        statusLine = line.removePrefix("VCX-STATS ")

        val extra = "VCX-EXTRA dup=${c.duplicates} trims=${c.trims} trimmed_packets=${c.trimmedPackets} " +
            "rebuffers=${c.rebuffers} resyncs=${c.resyncs} bad=$badPackets primed=${c.primed} frames=${c.framesPerPacket}"
        if (extra != lastExtra) {
            Log.i(TAG, extra)
            lastExtra = extra
        }

        val dst = lastSource ?: return
        if (audioSocket.isClosed) return
        val bytes = PacketCodec.buildStats(statsSeq++, nowUs(), snap)
        try {
            audioSocket.send(DatagramPacket(bytes, bytes.size, dst))
        } catch (e: Exception) {
            Log.w(TAG, "stats send to $dst failed: $e")
        }
    }

    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
}
