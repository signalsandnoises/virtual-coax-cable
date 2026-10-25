package com.vcx.receiver.core

/** Minimal key-value persistence (SharedPreferences on Android, [MemoryStore] in tests). */
interface KeyValueStore {
    fun getInt(key: String, def: Int): Int
    fun getString(key: String, def: String?): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    /** Writes all values in one batch; a null value removes the key. */
    fun put(values: Map<String, Any?>)
}

class MemoryStore : KeyValueStore {
    val map = HashMap<String, Any?>()
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun getString(key: String, def: String?) = if (map.containsKey(key)) map[key] as String? else def
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun put(values: Map<String, Any?>) {
        for ((k, v) in values) if (v == null) map.remove(k) else map[k] = v
    }
}

/** Read access to the START intent's extras (adb overrides). */
interface ExtrasReader {
    fun has(key: String): Boolean
    /** Int extra; Boolean extras map to 1/0 and numeric String extras are parsed. */
    fun int(key: String, def: Int): Int
    fun string(key: String): String?

    object None : ExtrasReader {
        override fun has(key: String) = false
        override fun int(key: String, def: Int) = def
        override fun string(key: String): String? = null
    }
}

/**
 * User settings, persisted under the same names as the adb extras. Defaults are the best hardware
 * configuration measured so far (target 40 ms, track_bursts 6, Location off while playing).
 */
data class ReceiverSettings(
    val targetMs: Int = 40,
    val trackBursts: Int = 6,
    val wifiMode: String = "low_latency",
    val locationOff: Boolean = true,
    /** Sender IP to accept, or [AUTO]. */
    val pc: String = AUTO,
    /** Output device key "type:productName", or [DEFAULT_DEVICE]. */
    val outputDevice: String = DEFAULT_DEVICE,
    val steer: Boolean = true,
    val align: Boolean = true,
    val silTrim: Boolean = true,
    /** Frames per AudioTrack write; 0 = one HAL burst. */
    val writeFrames: Int = 0,
    val stopOnUnplug: Boolean = true,
    val keepScreenOn: Boolean = false,
) {
    fun normalized() = copy(
        targetMs = targetMs.coerceIn(TARGET_MIN, TARGET_MAX),
        trackBursts = trackBursts.coerceIn(1, 32),
        wifiMode = if (wifiMode in WIFI_MODES) wifiMode else "low_latency",
        pc = pc.ifBlank { AUTO },
        outputDevice = outputDevice.ifBlank { DEFAULT_DEVICE },
        writeFrames = writeFrames.coerceIn(0, Vcx.MAX_FRAMES_PER_PACKET),
    )

    fun save(kv: KeyValueStore) {
        val s = normalized()
        kv.put(
            mapOf(
                K_TARGET to s.targetMs, K_BURSTS to s.trackBursts, K_WIFI to s.wifiMode, K_LOCATION to s.locationOff,
                K_PC to s.pc, K_OUTPUT to s.outputDevice, K_STEER to s.steer, K_ALIGN to s.align, K_SILTRIM to s.silTrim,
                K_WRITE to s.writeFrames, K_UNPLUG to s.stopOnUnplug, K_SCREEN to s.keepScreenOn,
            ),
        )
    }

    companion object {
        const val AUTO = "auto"
        const val DEFAULT_DEVICE = "default"
        const val TARGET_MIN = 20
        const val TARGET_MAX = 120
        val TRACK_BURST_OPTIONS = listOf(2, 3, 4, 6, 8, 12)
        val WIFI_MODES = listOf("low_latency", "high_perf", "none")

        const val K_TARGET = "target_ms"
        const val K_BURSTS = "track_bursts"
        const val K_WIFI = "wifi_mode"
        const val K_LOCATION = "location_off"
        const val K_PC = "pc"
        const val K_OUTPUT = "output_device"
        const val K_STEER = "steer"
        const val K_ALIGN = "align"
        const val K_SILTRIM = "siltrim"
        const val K_WRITE = "write_frames"
        const val K_UNPLUG = "stop_on_unplug"
        const val K_SCREEN = "keep_screen_on"

        fun load(kv: KeyValueStore): ReceiverSettings {
            val d = ReceiverSettings()
            return ReceiverSettings(
                targetMs = kv.getInt(K_TARGET, d.targetMs),
                trackBursts = kv.getInt(K_BURSTS, d.trackBursts),
                wifiMode = kv.getString(K_WIFI, d.wifiMode) ?: d.wifiMode,
                locationOff = kv.getBoolean(K_LOCATION, d.locationOff),
                pc = kv.getString(K_PC, d.pc) ?: d.pc,
                outputDevice = kv.getString(K_OUTPUT, d.outputDevice) ?: d.outputDevice,
                steer = kv.getBoolean(K_STEER, d.steer),
                align = kv.getBoolean(K_ALIGN, d.align),
                silTrim = kv.getBoolean(K_SILTRIM, d.silTrim),
                writeFrames = kv.getInt(K_WRITE, d.writeFrames),
                stopOnUnplug = kv.getBoolean(K_UNPLUG, d.stopOnUnplug),
                keepScreenOn = kv.getBoolean(K_SCREEN, d.keepScreenOn),
            ).normalized()
        }
    }
}

/** What one START actually runs with. */
data class EffectiveConfig(
    val targetMs: Int,
    val trackBursts: Int,
    val wifiMode: String,
    val locationOff: Boolean,
    val steer: Boolean,
    val align: Boolean,
    val silTrim: Boolean,
    val writeFrames: Int,
    /** Only accept this sender IP; null = any. */
    val pcIp: String?,
    /** Preferred output device key; null = system routing. */
    val outputDevice: String?,
    val stopOnUnplug: Boolean,
)

/** Extras present on the START intent override the saved settings; absent extras use the settings. */
object ConfigResolver {
    fun resolve(s: ReceiverSettings, x: ExtrasReader): EffectiveConfig {
        fun bool(key: String, saved: Boolean) = if (x.has(key)) x.int(key, if (saved) 1 else 0) != 0 else saved
        fun int(key: String, saved: Int, lo: Int, hi: Int) = if (x.has(key)) x.int(key, saved).coerceIn(lo, hi) else saved
        val wifi = x.string(ReceiverSettings.K_WIFI)?.takeIf { it in ReceiverSettings.WIFI_MODES } ?: s.wifiMode
        val pc = x.string(ReceiverSettings.K_PC) ?: s.pc
        val out = x.string(ReceiverSettings.K_OUTPUT) ?: s.outputDevice
        return EffectiveConfig(
            // adb may go outside the UI's 20-120 ms slider range for experiments.
            targetMs = int(ReceiverSettings.K_TARGET, s.targetMs, 5, 1000),
            trackBursts = int(ReceiverSettings.K_BURSTS, s.trackBursts, 1, 32),
            wifiMode = wifi,
            locationOff = bool(ReceiverSettings.K_LOCATION, s.locationOff),
            steer = bool(ReceiverSettings.K_STEER, s.steer),
            align = bool(ReceiverSettings.K_ALIGN, s.align),
            silTrim = bool(ReceiverSettings.K_SILTRIM, s.silTrim),
            writeFrames = int(ReceiverSettings.K_WRITE, s.writeFrames, 0, Vcx.MAX_FRAMES_PER_PACKET),
            pcIp = pc.takeIf { it.isNotBlank() && it != ReceiverSettings.AUTO },
            outputDevice = out.takeIf { it.isNotBlank() && it != ReceiverSettings.DEFAULT_DEVICE },
            stopOnUnplug = bool(ReceiverSettings.K_UNPLUG, s.stopOnUnplug),
        )
    }
}

/** Last-seen senders (PC selection list), newest first, at most [MAX]. */
object KnownSenders {
    const val KEY = "known_senders"
    const val MAX = 8

    data class Sender(val ip: String, val name: String?, val lastSeenMs: Long)

    fun load(kv: KeyValueStore): List<Sender> {
        val raw = kv.getString(KEY, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3 || p[0].isBlank()) null
            else Sender(p[0], p[1].ifBlank { null }, p[2].toLongOrNull() ?: 0L)
        }.sortedByDescending { it.lastSeenMs }
    }

    /** Records a sighting; keeps a known name when the new sighting has none. */
    fun note(kv: KeyValueStore, ip: String, name: String?, nowMs: Long) {
        val list = load(kv).toMutableList()
        val old = list.find { it.ip == ip }
        list.removeAll { it.ip == ip }
        val cleanName = name?.replace('\t', ' ')?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
        list.add(0, Sender(ip, cleanName ?: old?.name, nowMs))
        save(kv, list.sortedByDescending { it.lastSeenMs }.take(MAX))
    }

    fun clear(kv: KeyValueStore) = kv.put(mapOf(KEY to null))

    private fun save(kv: KeyValueStore, list: List<Sender>) =
        kv.put(mapOf(KEY to list.joinToString("\n") { "${it.ip}\t${it.name ?: ""}\t${it.lastSeenMs}" }))
}
