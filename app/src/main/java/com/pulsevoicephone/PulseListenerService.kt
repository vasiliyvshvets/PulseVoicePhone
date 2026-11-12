package com.pulsevoicephone

import android.speech.tts.TextToSpeech
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.MessageEvent
import java.util.Locale

class PulseListenerService : WearableListenerService() {
    companion object { var onPulse: ((Int) -> Unit)? = null }
    private var tts: TextToSpeech? = null
    private var lastSpoken = 0L
    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
    }
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != "/pulse") return
        val bpm = messageEvent.data.toString(Charsets.UTF_8).toIntOrNull() ?: return
        if (bpm !in 30..240) return
        onPulse?.invoke(bpm)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpoken >= 10000) {
            tts?.speak("Пульс $bpm", TextToSpeech.QUEUE_FLUSH, null, "pulse")
            lastSpoken = now
        }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
