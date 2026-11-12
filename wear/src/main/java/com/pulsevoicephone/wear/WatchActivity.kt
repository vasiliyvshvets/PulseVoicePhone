package com.pulsevoicephone.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import com.google.android.gms.wearable.Wearable

class WatchActivity : Activity(), SensorEventListener {
    private lateinit var manager: SensorManager
    private var sensor: Sensor? = null
    private lateinit var label: TextView
    private var active = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        label = TextView(this).apply { text = "Пульс: —"; textSize = 22f; gravity = Gravity.CENTER }
        val button = Button(this).apply {
            text = "Старт / стоп"
            setOnClickListener { if (active) stop() else start() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label)
            addView(button)
        })
    }
    private fun start() {
        if (sensor == null) { label.text = "Датчик не найден"; return }
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1)
            return
        }
        active = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        label.text = if (active) "Измерение…" else "Ошибка датчика"
    }
    private fun stop() { manager.unregisterListener(this); active = false; label.text = "Остановлено" }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        if (bpm !in 30..240) return
        label.text = "Пульс: $bpm"
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, "/pulse", bpm.toString().toByteArray())
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
    }
    override fun onPause() { if (active) stop(); super.onPause() }
}
