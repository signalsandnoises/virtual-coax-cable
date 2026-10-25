package com.vcx.receiver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.vcx.receiver.core.EngineSnapshot
import com.vcx.receiver.core.ExtrasReader
import com.vcx.receiver.core.KeyValueStore
import com.vcx.receiver.core.KnownSenders
import com.vcx.receiver.core.ReceiverSettings
import com.vcx.receiver.core.StatsHistory
import java.util.concurrent.CopyOnWriteArrayList

/** SharedPreferences-backed [KeyValueStore]. */
class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    override fun getString(key: String, def: String?) = prefs.getString(key, def)
    override fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    override fun put(values: Map<String, Any?>) {
        val e = prefs.edit()
        for ((k, v) in values) {
            when (v) {
                null -> e.remove(k)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Long -> e.putLong(k, v)
                else -> e.putString(k, v.toString())
            }
        }
        e.apply()
    }

    companion object {
        fun of(context: Context) =
            PrefsStore(context.applicationContext.getSharedPreferences("vcx_settings", Context.MODE_PRIVATE))
    }
}

object SettingsRepo {
    fun load(context: Context) = ReceiverSettings.load(PrefsStore.of(context))
    fun save(context: Context, s: ReceiverSettings) = s.save(PrefsStore.of(context))
    fun update(context: Context, f: (ReceiverSettings) -> ReceiverSettings) = save(context, f(load(context)))
}

/** [ExtrasReader] over a START intent (adb `--ei`, `--ez`, `--es`). */
class IntentExtras(private val intent: Intent?) : ExtrasReader {
    override fun has(key: String) = intent?.hasExtra(key) == true

    @Suppress("DEPRECATION")
    override fun int(key: String, def: Int): Int = when (val v = intent?.extras?.get(key)) {
        is Int -> v
        is Long -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull() ?: when (v.trim().lowercase()) { "true" -> 1; "false" -> 0; else -> def }
        else -> def
    }

    @Suppress("DEPRECATION")
    override fun string(key: String): String? = intent?.extras?.get(key)?.toString()
}

/**
 * Process-wide playback state. The service publishes an [EngineSnapshot] once a second (and null when
 * it stops); the activity, Quick Settings tile and notification observe it. Same process as the
 * service, so a re-opened or rotated activity just reads the latest value; after process death there
 * is no service either and the state is correctly "idle".
 */
object PlayerHub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile var latest: EngineSnapshot? = null
        private set
    /** Main thread only. */
    val history = StatsHistory()

    fun publish(s: EngineSnapshot?) {
        main.post {
            latest = s
            if (s == null || !s.running) history.clear() else history.add(s)
            for (l in listeners) l.run()
        }
    }

    fun addListener(l: Runnable) { listeners.add(l) }
    fun removeListener(l: Runnable) { listeners.remove(l) }

    fun noteSender(context: Context, ip: String, name: String?) {
        val app = context.applicationContext
        main.post { KnownSenders.note(PrefsStore.of(app), ip, name, System.currentTimeMillis()) }
    }
}

/** Output devices the user can pick, keyed by "type:productName" (ids change on replug). */
object OutputDevices {
    private val PICKABLE = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    fun list(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in PICKABLE }
            .distinctBy { key(it) }

    fun key(d: AudioDeviceInfo) = "${d.type}:${d.productName}"

    fun find(context: Context, key: String): AudioDeviceInfo? = list(context).find { key(it) == key }

    fun isBluetooth(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (adds 100+ ms)"
            else -> "Output"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        return if (name != null) "$kind · $name" else kind
    }
}
