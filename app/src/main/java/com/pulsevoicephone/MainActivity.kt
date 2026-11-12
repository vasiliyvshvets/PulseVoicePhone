package com.pulsevoicephone

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val listener: (Int) -> Unit = { bpm -> runOnUiThread { status.text = "Пульс с часов: $bpm уд/мин" } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            text = "Запустите измерение на Galaxy Watch 8. Озвучивание каждые 10 секунд."
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(32,32,32,32)
            addView(status)
        })
    }
    override fun onStart() { super.onStart(); PulseListenerService.onPulse = listener }
    override fun onStop() { PulseListenerService.onPulse = null; super.onStop() }
}
