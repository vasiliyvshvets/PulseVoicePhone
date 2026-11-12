package com.pulsevoicephone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {
    private lateinit var manager: SensorManager
    private var sensor: Sensor? = null
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private var listening = false
    private var lastSpoken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
        }
        status = TextView(this).apply {
            textSize = 23f
            gravity = Gravity.CENTER
            text = if (sensor == null) "Датчик пульса не найден на устройстве" else "Нажмите «Начать»"
        }
        val button = Button(this).apply {
            text = "Начать / остановить"
            setOnClickListener { if (listening) stopReading() else startReading() }
        }
        layout.addView(status)
        layout.addView(button)
        setContentView(layout)
    }

    private fun startReading() {
        if (sensor == null) {
            status.text = "Нет встроенного датчика пульса. Нужен совместимый датчик или другой способ измерения."
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 10)
            return
        }
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        status.text = if (listening) "Ожидание измерения…" else "Не удалось включить датчик"
    }

    private fun stopReading() {
        manager.unregisterListener(this)
        listening = false
        status.text = "Измерение остановлено"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        if (bpm <= 0) return
        status.text = "Пульс: $bpm уд/мин"
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpoken >= 10000) {
            tts?.speak("Пульс $bpm", TextToSpeech.QUEUE_FLUSH, null, "pulse")
            lastSpoken = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReading()
            else status.text = "Для измерения требуется разрешение на датчики тела"
        }
    }

    override fun onPause() {
        super.onPause()
        if (listening) stopReading()
    }

    override fun onDestroy() {
        manager.unregisterListener(this)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
