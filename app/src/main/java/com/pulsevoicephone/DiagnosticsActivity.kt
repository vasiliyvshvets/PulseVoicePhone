package com.pulsevoicephone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.Locale

class DiagnosticsActivity : ComponentActivity() {
    private lateinit var reportView: TextView
    private lateinit var checkButton: Button
    private lateinit var speechButton: Button
    private var healthReport = "Проверка ещё не запускалась."
    private var voiceReport = "Инициализация синтеза речи…"
    private var heardReport = "Слышимость: пользователь ещё не подтвердил."
    private var speech: TextToSpeech? = null
    private var voiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title
            setOnClickListener { action() }
            layout.addView(this)
        }
        layout.addView(TextView(this).apply {
            textSize = 22f
            text = "Диагностика PulseVoicePhone"
        })
        layout.addView(TextView(this).apply {
            text = "Отчёт содержит модель телефона, версии, разрешения, возраст данных и " +
                "журнал ошибок без значений пульса. Ничего не отправляется автоматически.\n" +
                "Проверка читает Samsung Health через Health Connect; измерение на часах не запускается."
        })
        checkButton = button("Проверить Health Connect") { runChecks() }
        speechButton = button("Проверить русский голос") { testSpeech() }.apply { isEnabled = false }
        button("Голос слышно") {
            heardReport = "Слышимость: пользователь подтвердил, что голос слышно."
            DiagnosticLog.event("voice_audible_confirmed")
            render()
        }
        button("Голос не слышно") {
            heardReport = "Слышимость: пользователь сообщил, что голос не слышно."
            DiagnosticLog.event("voice_not_audible")
            render()
        }
        button("Скопировать отчёт") {
            render()
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("PulseVoicePhone diagnostics", reportView.text))
        }
        button("Поделиться отчётом") {
            render()
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PulseVoicePhone — диагностика")
                putExtra(Intent.EXTRA_TEXT, reportView.text.toString())
            }, "Отправить отчёт"))
        }
        button("Очистить журнал") { DiagnosticLog.clear(); render() }
        button("Назад") { finish() }
        reportView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            layout.addView(this)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        render()
        speech = TextToSpeech(applicationContext) { status ->
            if (isDestroyed) return@TextToSpeech
            val language = if (status == TextToSpeech.SUCCESS)
                speech?.setLanguage(Locale("ru", "RU")) else null
            voiceReady = language != null && language >= TextToSpeech.LANG_AVAILABLE
            speechButton.isEnabled = voiceReady
            voiceReport = if (voiceReady) "Русский голос доступен. Нажмите кнопку проверки."
                else "Русский голос не готов. Проверьте установку русского голоса в настройках TTS."
            DiagnosticLog.event("diagnostics_tts_init status=$status language=$language")
            speech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = voiceEvent("Синтез речи начался.", "tts_started")
                override fun onDone(id: String?) = voiceEvent(
                    "Синтез речи завершён. Подтвердите, слышно ли голос.", "tts_completed")
                @Deprecated("Legacy callback")
                override fun onError(id: String?) = voiceEvent("Ошибка синтеза речи.", "tts_error")
                override fun onError(id: String?, code: Int) =
                    voiceEvent("Ошибка синтеза речи: $code", "tts_error code=$code")
            })
            render()
        }
    }

    private fun voiceEvent(message: String, event: String) {
        DiagnosticLog.event(event)
        runOnUiThread {
            if (!isDestroyed) {
                voiceReport = message
                render()
            }
        }
    }

    private fun testSpeech() {
        if (!voiceReady) return
        heardReport = "Слышимость: подтвердите результат новой проверки."
        val result = speech?.speak("Проверка русского голоса. Если вы слышите эту фразу, нажмите «Голос слышно».",
            TextToSpeech.QUEUE_FLUSH, null, "diagnostic")
        voiceReport = "Запрос тестовой озвучки: $result"
        DiagnosticLog.event("tts_test_request result=$result")
        render()
    }

    private fun runChecks() {
        checkButton.isEnabled = false
        healthReport = "Проверка…"
        render()
        lifecycleScope.launch {
            try {
                withTimeout(20_000) {
                    val status = HealthConnectClient.getSdkStatus(this@DiagnosticsActivity)
                    val statusText = when (status) {
                        HealthConnectClient.SDK_AVAILABLE -> "доступен"
                        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "нужна установка/обновление"
                        else -> "недоступен"
                    }
                    healthReport = "Health Connect: $statusText (код $status)."
                    DiagnosticLog.event("diagnostics_hc_status=$status")
                    if (status == HealthConnectClient.SDK_AVAILABLE) {
                        val client = HealthConnectClient.getOrCreate(this@DiagnosticsActivity)
                        val backgroundSupported = SessionAccess.backgroundSupported(client)
                        val permissions = client.permissionController.getGrantedPermissions()
                        val backgroundGranted = permissions.contains(
                            androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                        healthReport += "\nФоновое чтение: поддерживается=$backgroundSupported, разрешено=$backgroundGranted."
                        DiagnosticLog.event("background_supported=$backgroundSupported background_granted=$backgroundGranted")
                        val granted = permissions.contains(
                            HealthPermission.getReadPermission(HeartRateRecord::class))
                        healthReport += "\nЧтение пульса: " + if (granted) "разрешено." else "не разрешено."
                        DiagnosticLog.event("diagnostics_read_permission=$granted")
                        if (granted) {
                            val now = Instant.now()
                            val result = HealthRepository(client).inspectSamsungPulse(now)
                            healthReport += "\nSamsung Health, последние 24 часа: " +
                                "${result.records} записей, ${result.samples} отсчётов, ${result.pages} страниц."
                            val sample = result.latest
                            healthReport += if (sample == null) {
                                "\nПодходящих показаний нет. Проверьте запись пульса Samsung Health в " +
                                    "Health Connect и синхронизацию часов."
                            } else {
                                val age = Duration.between(sample.time, Instant.now()).seconds
                                "\nВозраст последнего показания: $age с.\n" +
                                    if (PulsePolicy.isFresh(sample, Instant.now())) "Данные свежие; озвучка разрешена."
                                    else "Данные устарели; озвучка приостановлена. Ожидаем синхронизацию."
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                healthReport = "Health Connect не ответил за 20 секунд. Попробуйте ещё раз."
                DiagnosticLog.event("diagnostics_read_timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                healthReport = "Проверка завершилась ошибкой ${e.javaClass.simpleName}. " +
                    "Проверьте доступ к пульсу и состояние Health Connect."
                DiagnosticLog.event("diagnostics_error class=${e.javaClass.name}")
            } finally {
                checkButton.isEnabled = true
                render()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun render() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val audio = getSystemService(AudioManager::class.java)
        reportView.text = "PulseVoicePhone $version\n" +
            "Отчёт: ${Instant.now()}\n" +
            "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}\n" +
            "Сеанс активен: ${PulseSessionService.status.value.running}\n" +
            "Громкость мультимедиа: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
            "${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}\n\n" +
            "$healthReport\n\n$voiceReport\n$heardReport\n\n" +
            "Журнал (последние 100 событий):\n${DiagnosticLog.report()}"
    }

    override fun onStop() {
        speech?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }
}
