package com.pulsevoicephone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.Instant

object DiagnosticLog {
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    }

    @Synchronized fun event(message: String, synchronous: Boolean = false) {
        val line = "${Instant.now()} ${message.replace('\n', ' ').replace('\r', ' ').take(1000)}"
        Log.d("PulseVoicePhone", line)
        val editor = prefs?.edit()?.putString("events",
            DiagnosticBuffer.append(prefs?.getString("events", "") ?: "", line))
        if (synchronous) editor?.commit() else editor?.apply()
    }

    @Synchronized fun report(): String = prefs?.getString("events", "") ?: ""
    @Synchronized fun clear() { prefs?.edit()?.clear()?.apply() }
}

class PulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        DiagnosticLog.event("app_start")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Do not persist exception messages, record payloads or heart rate values.
                DiagnosticLog.event("uncaught ${error.javaClass.name} " +
                    error.stackTrace.take(12).joinToString(" | "), synchronous = true)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
