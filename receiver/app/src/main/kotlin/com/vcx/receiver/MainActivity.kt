package com.vcx.receiver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Bare milestone-1 UI: target ms, Start/Stop (same intents as adb), status text. */
class MainActivity : Activity() {

    private lateinit var target: EditText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = ReceiverService.statusText()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        target = EditText(this).apply {
            hint = "target ms"
            setText("40")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val start = Button(this).apply { text = "Start"; setOnClickListener { send(ReceiverService.ACTION_START) } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { send(ReceiverService.ACTION_STOP) } }
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 3, pad, pad)
                addView(TextView(this@MainActivity).apply { text = "Target buffer (ms)" })
                addView(target)
                addView(start)
                addView(stop)
                addView(status)
            },
        )

        // Restore Location if a previous stream died without doing so (crash / force-stop).
        androidLocationGuard(this).recoverIfNeeded(streaming = ReceiverService.isStreaming())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun send(action: String) {
        val i = Intent(this, ReceiverService::class.java).setAction(action)
        if (action == ReceiverService.ACTION_START) {
            i.putExtra(ReceiverService.EXTRA_TARGET_MS, target.text.toString().toIntOrNull() ?: 40)
            i.putExtra(ReceiverService.EXTRA_WIFI_MODE, "low_latency")
        }
        startForegroundService(i)
    }
}
