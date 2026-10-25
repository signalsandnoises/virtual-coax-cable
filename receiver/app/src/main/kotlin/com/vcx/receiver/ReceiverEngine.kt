package com.vcx.receiver

import android.content.Context
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.vcx.receiver.core.AudioPacket
import com.vcx.receiver.core.DelayStats
import com.vcx.receiver.core.JitterBuffer
import com.vcx.receiver.core.PacketCodec
import com.vcx.receiver.core.PlayoutChain
import com.vcx.receiver.core.StatsFormat
import com.vcx.receiver.core.StatsSnapshot
import com.vcx.receiver.core.UsClock
import com.vcx.receiver.core.Vcx
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale

const val TAG = "VCX"

/**
 * Owns the sockets, jitter buffer, AudioTrack and the four threads (rx, playout, discovery, stats)
 * for one START. stop() tears everything down; a new START builds a new engine.
 *
 * The rx and playout loops are allocation-free per packet / per write: one reused AudioPacket, the
 * audio socket connected to the sender after its first packet (so libcore does not allocate a source
 * address per receive), preallocated buffers, a primitive clock and no per-write string building.
 */
class ReceiverEngine(
    private val context: Context,
    val targetMs: Int,
    val wifiMode: String,
    val trackBursts: Int,
    val steer: Boolean,
    val align: Boolean,
    val silTrim: Boolean,
    /** Frames per AudioTrack write; 0 = one HAL burst. */
    private val writeFramesRequested: Int = 0,
    /** Only for the VCX-START line; LocationGuard (in the service) does the work. */
    private val locationOff: Boolean = false,
) {
    @Volatile private var running = false
    @Volatile private var lastSource: SocketAddress? = null
    @Volatile var statusLine: String = "starting"
        private set
    @Volatile private var badPackets = 0L

    private val jitter = JitterBuffer(targetMs, log = { Log.i(TAG, it) })
    private val delayStats = DelayStats()
    private val clock = UsClock { SystemClock.elapsedRealtimeNanos() / 1000 }
    /** JitterBuffer -> resampler, with start-up alignment, lead steering and silence trim. */
    private val chain = PlayoutChain(
        jitter, steer = steer, align = align, silTrim = silTrim, delay = delayStats, clockUs = clock,
        log = { Log.i(TAG, it) },
    )

    private lateinit var output: AudioOutput
    private lateinit var audioSocket: DatagramSocket
    private lateinit var discoverySocket: DatagramSocket
    private val threads = mutableListOf<Thread>()
    @Volatile private var underrunBase = 0
    var writeFrames = 0
        private set

    private val play = PlayStats()
    @Volatile private var playoutTid = 0

    fun start() {
        output = AudioOutput(context, trackBursts)
        writeFrames = if (writeFramesRequested > 0) writeFramesRequested.coerceIn(16, Vcx.MAX_FRAMES_PER_PACKET)
        else output.burstFrames
        try {
            audioSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 1 shl 20
                soTimeout = 500
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
                "write_frames=$writeFrames steer=${if (steer) 1 else 0} align=${if (align) 1 else 0} " +
                "siltrim=${if (silTrim) 1 else 0} location_off=${if (locationOff) 1 else 0} transfer_mode=stream " +
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
        threads.forEach { it.interrupt(); it.join(700) }
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
        Log.i(TAG, "$name priority=${Process.getThreadPriority(Process.myTid())} tid=${Process.myTid()}")
    }

    private fun playoutLoop() {
        setUrgentAudioPriority("playout")
        playoutTid = Process.myTid()
        val frames = writeFrames
        val bytes = frames * Vcx.BYTES_PER_FRAME
        val buf = ByteArray(bytes)
        val silence = ByteArray(bytes)
        val track = output.track
        val ts = AudioTimestamp()
        output.play()
        underrunBase = track.underrunCount
        var lastUnderruns = underrunBase
        var written = 0L
        var lastReturnNs = System.nanoTime()
        var tsCountdown = 0
        while (running) {
            val n = chain.read(buf, frames)
            // Not primed yet: keep the track fed with silence so the blocking write keeps pacing us.
            val src = if (n > 0) buf else silence
            val qBefore = written.toInt() - track.playbackHeadPosition
            val t0 = System.nanoTime()
            var off = 0
            var short = false
            var failed = false
            while (off < bytes) {
                val r = track.write(src, off, bytes - off, AudioTrack.WRITE_BLOCKING)
                if (r <= 0) { short = true; failed = true; break }
                if (r < bytes - off) short = true
                off += r
            }
            val t1 = System.nanoTime()
            written += off / Vcx.BYTES_PER_FRAME
            val qAfter = written.toInt() - track.playbackHeadPosition
            var qts = Int.MIN_VALUE
            if (--tsCountdown <= 0) {
                tsCountdown = 4
                if (track.getTimestamp(ts)) {
                    val pos = ts.framePosition + (t1 - ts.nanoTime) * Vcx.SAMPLE_RATE / 1_000_000_000L
                    qts = (written - pos).toInt()
                }
            }
            play.record(t1 - lastReturnNs, t1 - t0, short, qBefore, qAfter, qts)
            lastReturnNs = t1
            val u = track.underrunCount
            if (u != lastUnderruns) {
                play.markUnderrun(u - lastUnderruns, u - underrunBase, chain.lastDepthFrames, chain.lastLeadFrames)
                lastUnderruns = u
            }
            if (failed && running) {
                Log.w(TAG, "AudioTrack write failed; playout thread exiting")
                break
            }
        }
    }

    private fun receiveLoop() {
        setUrgentAudioPriority("rx")
        val buf = ByteArray(65536)
        val dp = DatagramPacket(buf, buf.size)
        val pkt = AudioPacket()
        var lastRxMs = 0L
        while (running) {
            dp.setLength(buf.size)
            try {
                audioSocket.receive(dp)
            } catch (e: SocketTimeoutException) {
                if (audioSocket.isConnected && SystemClock.elapsedRealtime() - lastRxMs > 2000) {
                    audioSocket.disconnect()
                    Log.i(TAG, "VCX-RX idle 2 s, disconnected; accepting any sender")
                }
                continue
            } catch (e: PortUnreachableException) {
                continue // ICMP for a STATS packet after the sender went away
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "rx: $e")
                continue
            }
            val arrivalUs = clock.nowUs()
            if (!PacketCodec.parseAudioInto(buf, 0, dp.length, pkt)) {
                badPackets++
                continue
            }
            lastRxMs = SystemClock.elapsedRealtime()
            if (!audioSocket.isConnected) {
                // Once per sender: connecting lets libcore skip the per-receive source-address allocation.
                val src = dp.socketAddress
                lastSource = src
                try {
                    audioSocket.connect(src)
                    Log.i(TAG, "VCX-RX connected to $src")
                } catch (e: Exception) {
                    Log.w(TAG, "rx connect to $src failed: $e")
                }
            }
            delayStats.onArrival(pkt.seq, pkt.frames, arrivalUs)
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
            val reply = PacketCodec.buildDiscoverReply(replySeq++, clock.nowUs(), Vcx.AUDIO_PORT, Build.MODEL)
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
    private var lastGc = LongArray(4) { -1 }
    private val gcDelta = LongArray(4)
    private val uVals = LongArray(8)
    private val uDepthLead = DoubleArray(2)
    private val uTotal = IntArray(1)

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

    private fun readGcDeltas() {
        val keys = GC_KEYS
        for (i in keys.indices) {
            val v = Debug.getRuntimeStat(keys[i])?.toLongOrNull() ?: -1
            gcDelta[i] = if (v < 0 || lastGc[i] < 0) -1 else v - lastGc[i]
            lastGc[i] = v
        }
    }

    /** CPU the playout thread last ran on: field 39 of /proc/self/task/<tid>/stat, or -1. */
    private fun playoutCpu(): Int {
        val tid = playoutTid
        if (tid == 0) return -1
        return try {
            val s = File("/proc/self/task/$tid/stat").readText()
            val rest = s.substring(s.lastIndexOf(')') + 2).split(' ')
            rest[39 - 3].toInt() // fields after "(comm)" start at field 3
        } catch (_: Exception) {
            -1
        }
    }

    private fun fmtMs(ns: Long) = String.format(Locale.US, "%.2f", ns / 1e6)
    private fun fmtFrames(f: Double) = String.format(Locale.US, "%.1f", f * 1000.0 / Vcx.SAMPLE_RATE)

    private fun gcString() =
        "gc=${gcDelta[0]} gc_ms=${gcDelta[1]} bgc=${gcDelta[2]} bgc_ms=${gcDelta[3]} cpu=${playoutCpu()}"

    private fun logPlay() {
        readGcDeltas()
        val n = play.takeUnderrun(uVals, uDepthLead, uTotal)
        if (n > 0) {
            Log.i(
                TAG,
                "VCX-UNDERRUN count=+$n total=${uTotal[0]} depth_ms=${fmtFrames(uDepthLead[0])} " +
                    "lead_ms=${fmtFrames(uDepthLead[1])} gap_max_ms=${fmtMs(uVals[1])} write_max_ms=${fmtMs(uVals[2])} " +
                    "short_writes=${uVals[3]} writes=${uVals[0]} q_min=${uVals[4]} q_max=${uVals[5]} " +
                    "qts_min=${uVals[6]} qts_max=${uVals[7]} ${gcString()}",
            )
        }
        play.take()
        val p = play.snap
        Log.i(
            TAG,
            "VCX-PLAY writes=${p[0]} write_frames=$writeFrames gap_max_ms=${fmtMs(p[1])} write_max_ms=${fmtMs(p[2])} " +
                "short_writes=${p[3]} q_min=${p[4]} q_max=${p[5]} qts_min=${p[6]} qts_max=${p[7]} ${gcString()}",
        )
    }

    private fun logStats() {
        val c = jitter.counters()
        val delay = delayStats.takeSecond(clock.nowUs())
        val snap = StatsSnapshot(
            bufferUs = chain.depthUs(),
            targetUs = c.targetUs,
            targetMs = targetMs,
            received = c.received,
            lost = c.lost,
            recovered = c.recovered,
            concealed = c.concealed,
            late = c.late,
            underruns = (output.underrunCount() - underrunBase).toLong().coerceAtLeast(0),
            ratePpm = chain.ratePpm,
            trackFrames = output.bufferFrames,
            delayP99Ms = delay.delayP99Ms,
            delayMaxMs = delay.delayMaxMs,
            gapMaxMs = delay.gapMaxMs,
            depthMinMs = chain.takeDepthMinMs(),
        )
        val line = StatsFormat.logLine(snap)
        Log.i(TAG, line)
        statusLine = line.removePrefix("VCX-STATS ")
        logPlay()

        val extra = "VCX-EXTRA dup=${c.duplicates} caps=${c.caps} capped_packets=${c.cappedPackets} " +
            "align_dropped=${c.alignDropped} align_inserted=${c.alignInserted} rebuffers=${c.rebuffers} " +
            "resyncs=${c.resyncs} bad=$badPackets max_k=${c.maxK} sil_dropped=${c.silDropped} sil_inserted=${c.silInserted} " +
            "primed=${c.primed} frames=${c.framesPerPacket}"
        if (extra != lastExtra) {
            Log.i(TAG, extra)
            lastExtra = extra
        }

        val dst = lastSource ?: return
        if (audioSocket.isClosed) return
        val bytes = PacketCodec.buildStats(statsSeq++, clock.nowUs(), snap)
        try {
            audioSocket.send(DatagramPacket(bytes, bytes.size, dst))
        } catch (e: Exception) {
            Log.w(TAG, "stats send to $dst failed: $e")
        }
    }

    private companion object {
        val GC_KEYS = arrayOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
    }
}
