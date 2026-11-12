package com.pulsevoicephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.Locale

data class SessionStatus(
    val running: Boolean = false,
    val sample: PulseSample? = null,
    val message: String = "Перед пробежкой разрешите фоновое чтение и запустите сеанс.",
    val voice: String = "Голос ещё не запущен"
)

class PulseSessionService : Service() {
    companion object {
        const val START = "com.pulsevoicephone.START_SESSION"
        const val STOP = "com.pulsevoicephone.STOP_SESSION"
        const val SET_VOICE = "com.pulsevoicephone.SET_VOICE"
        private const val CHANNEL = "pulse_session"
        private const val NOTIFICATION_ID = 10
        private val mutableStatus = MutableStateFlow(SessionStatus())
        val status = mutableStatus.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var voiceEnabled = true
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Озвучка пульса во время пробежки", NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseVoicePhone:session")
            .apply { setReferenceCounted(false) }
        tts = TextToSpeech(applicationContext) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale("ru", "RU"))
                voiceReady = language != null && language >= TextToSpeech.LANG_AVAILABLE
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            mutableStatus.value = mutableStatus.value.copy(
                voice = if (voiceReady) "Русский голос готов" else "Русский голос недоступен. Проверьте настройки TTS."
            )
            DiagnosticLog.event("session_tts_ready=$voiceReady code=$code")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                finishSession("Сеанс остановлен.")
                return START_NOT_STICKY
            }
            SET_VOICE -> {
                voiceEnabled = intent.getBooleanExtra("voice", true)
                if (!voiceEnabled) tts?.stop()
                return START_NOT_STICKY
            }
        }
        voiceEnabled = intent?.getBooleanExtra("voice", true) ?: true
        try {
            val notification = notification("Подготовка чтения пульса…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            DiagnosticLog.event("session_foreground_error class=${e.javaClass.name}")
            finishSession("Android не разрешил запуск сеанса. Откройте приложение и попробуйте снова.")
            return START_NOT_STICKY
        }
        if (loop?.isActive == true) return START_NOT_STICKY
        mutableStatus.value = mutableStatus.value.copy(running = true, message = "Сеанс запускается…")
        DiagnosticLog.event("session_started")
        loop = scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@PulseSessionService)
                if (!SessionAccess.backgroundSupported(client)) {
                    finishSession("Фоновое чтение Health Connect не поддерживается телефоном. Обновите Android/Health Connect.")
                    return@launch
                }
                while (true) {
                    val cycle = SystemClock.elapsedRealtime()
                    // Refresh a bounded non-reference-counted lock, released on every stop/destroy.
                    wakeLock?.acquire(60_000L)
                    try {
                        withTimeout(20_000L) {
                            if (!client.permissionController.getGrantedPermissions().containsAll(SessionAccess.permissions)) {
                                finishSession("Нет разрешения чтения пульса в фоне. Выдайте доступ и начните сеанс снова.")
                                return@withTimeout
                            }
                            val sample = HealthRepository(client).latestSamsungPulse(Instant.now())
                            val fresh = sample != null && PulsePolicy.isFresh(sample, Instant.now())
                            val message = when {
                                sample == null -> "Ожидаем запись пульса из Samsung Health."
                                !fresh -> "Данные старше 2 минут. Ожидаем синхронизацию Samsung Health."
                                else -> "Сеанс активен. Можно выключить экран телефона."
                            }
                            mutableStatus.value = mutableStatus.value.copy(sample = sample, message = message)
                            updateNotification(message)
                            DiagnosticLog.event("session_poll fresh=$fresh voice_enabled=$voiceEnabled")
                            if (fresh && voiceEnabled && voiceReady) {
                                val result = tts?.speak("Пульс ${sample!!.bpm}", TextToSpeech.QUEUE_FLUSH, null, "session")
                                DiagnosticLog.event("session_tts_request result=$result")
                            } else if (!fresh) {
                                tts?.stop()
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        unavailable("Health Connect не ответил. Повторяем чтение.", "session_read_timeout")
                    } catch (e: SecurityException) {
                        finishSession("Доступ к пульсу отозван. Разрешите фоновое чтение повторно.")
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unavailable("Ошибка чтения Health Connect. Повторяем через 10 секунд.",
                            "session_read_error class=${e.javaClass.name}")
                    }
                    if (!mutableStatus.value.running) break
                    delay((PulsePolicy.POLL_INTERVAL_MS - (SystemClock.elapsedRealtime() - cycle)).coerceAtLeast(1000L))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.event("session_error class=${e.javaClass.name}")
                finishSession("Не удалось запустить Health Connect. Проверьте разрешения и повторите запуск.")
            }
        }
        // Deliberately require a new user start after Android kills the process or after reboot.
        return START_NOT_STICKY
    }

    private fun unavailable(message: String, event: String) {
        tts?.stop()
        mutableStatus.value = mutableStatus.value.copy(sample = null, message = message)
        updateNotification(message)
        DiagnosticLog.event(event)
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PulseSessionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PulseVoicePhone — сеанс озвучки")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun updateNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun finishSession(message: String) {
        mutableStatus.value = mutableStatus.value.copy(running = false, sample = null, message = message)
        DiagnosticLog.event("session_stopped $message")
        tts?.stop()
        loop?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (mutableStatus.value.running) {
            mutableStatus.value = mutableStatus.value.copy(running = false, sample = null,
                message = "Сеанс завершён системой. Запустите его перед следующей пробежкой.")
        }
        DiagnosticLog.event("session_service_destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
