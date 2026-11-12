package com.pulsevoicephone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))
    private lateinit var bpmView: TextView
    private lateinit var statusView: TextView
    private lateinit var timeView: TextView
    private lateinit var voiceView: TextView
    private lateinit var toggleButton: Button
    private var monitoring = false
    private var voiceEnabled = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val permissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        statusView.text = if (granted.containsAll(requiredPermissions)) {
            "Доступ разрешён. Нажмите «Начать чтение»."
        } else {
            "Доступ к пульсу не разрешён. Нажмите «Разрешить чтение пульса»."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitoring = savedInstanceState?.getBoolean("monitoring") ?: false
        voiceEnabled = savedInstanceState?.getBoolean("voice") ?: true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun label(value: String, size: Float = 17f): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setPadding(0, dp(10), 0, dp(10))
                content.addView(this)
            }
        fun button(value: String, action: () -> Unit): Button =
            Button(this).apply {
                text = value
                setOnClickListener { action() }
                content.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        label("PulseVoicePhone", 27f)
        label("Пульс из Samsung Health")
        bpmView = label("— уд/мин", 42f)
        timeView = label("Измерений пока нет")
        statusView = label("Разрешите доступ к пульсу и начните чтение.")
        button("Разрешить чтение пульса") { requestAccess() }
        button("Настройки Health Connect") {
            if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
                open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } else {
                openProvider()
            }
        }
        toggleButton = button("") {
            monitoring = !monitoring
            updateButton()
            if (!monitoring) {
                tts?.stop()
                statusView.text = "Чтение остановлено."
            }
        }
        updateButton()
        CheckBox(this).apply {
            text = "Озвучивать по-русски каждые 10 секунд"
            isChecked = voiceEnabled
            setOnCheckedChangeListener { _, checked ->
                voiceEnabled = checked
                if (!checked) tts?.stop()
            }
            content.addView(this)
        }
        voiceView = label("Подготовка русского голоса…")
        button("Настройки голоса") { open(Intent("com.android.settings.TTS_SETTINGS")) }
        label("Настройка:\n" +
            "1. Часы должны быть сопряжены с телефоном через Galaxy Wearable.\n" +
            "2. В Samsung Health на телефоне откройте настройки Health Connect и " +
            "разрешите запись пульса. Название раздела зависит от версии Samsung Health.\n" +
            "3. Измерьте пульс в Samsung Health на часах и дождитесь синхронизации.")
        label("Здесь читаются сохранённые измерения Samsung Health за последние 24 часа. " +
            "Приложение на часы устанавливать не нужно.\n\n" +
            "Озвучиваются показания не старше 2 минут. При задержке синхронизации озвучка " +
            "приостанавливается. Чтение выполняется каждые 10 секунд, пока открыт этот экран. " +
            "Кнопки управляют чтением на телефоне; измерениями на часах управляет Samsung Health.")
        button("Использование данных") {
            startActivity(Intent(this, PermissionsRationaleActivity::class.java))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        tts = TextToSpeech(applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val languageResult = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = languageResult != null &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                voiceView.text = if (ttsReady) "Русский голос готов"
                    else "Русский голос недоступен. Установите его в настройках голоса."
            } else {
                voiceView.text = "Не удалось запустить синтез речи. Проверьте настройки голоса."
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (monitoring) refresh()
                    delay(if (monitoring) PulsePolicy.POLL_INTERVAL_MS else 250L)
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateButton() {
        toggleButton.text = if (monitoring) "Остановить чтение" else "Начать чтение"
    }

    private fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(this)

    private fun requestAccess() {
        try {
            when (sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> permissions.launch(requiredPermissions)
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> openProvider()
                else -> statusView.text = "Health Connect недоступен на этом устройстве."
            }
        } catch (e: Exception) {
            statusView.text = "Не удалось открыть запрос доступа. Проверьте Health Connect."
        }
    }

    private fun openProvider() {
        statusView.text = "Установите или обновите Health Connect и вернитесь в приложение."
        open(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            statusView.text = "Этот раздел недоступен. Откройте его в настройках телефона."
        }
    }

    private fun clearReading(message: String) {
        bpmView.text = "— уд/мин"
        timeView.text = "Нет доступного измерения"
        statusView.text = message
        tts?.stop()
    }

    private suspend fun refresh() {
        try {
            when (sdkStatus()) {
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    clearReading("Установите или обновите Health Connect кнопкой настроек.")
                    return
                }
                HealthConnectClient.SDK_AVAILABLE -> Unit
                else -> {
                    clearReading("Health Connect недоступен на этом устройстве.")
                    return
                }
            }
            val client = HealthConnectClient.getOrCreate(this)
            if (!client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)) {
                clearReading("Нет доступа к пульсу. Нажмите «Разрешить чтение пульса».")
                return
            }
            val sample = HealthRepository(client).latestSamsungPulse(Instant.now())
            // Ignore a completed read if the user stopped it while the provider was responding.
            if (!monitoring) return
            if (sample == null) {
                clearReading("Нет измерений Samsung Health за последние 24 часа. " +
                    "Проверьте запись пульса в Health Connect и синхронизацию часов.")
                return
            }
            val fresh = PulsePolicy.isFresh(sample, Instant.now())
            bpmView.text = "${sample.bpm} уд/мин"
            timeView.text = "Измерено: ${formatter.format(sample.time)}"
            statusView.text = if (fresh) "Свежее сохранённое измерение Samsung Health."
                else "Измерению больше 2 минут. Озвучка приостановлена до свежих данных."
            if (fresh && voiceEnabled && ttsReady) {
                val result = tts?.speak("Пульс ${sample.bpm}", TextToSpeech.QUEUE_FLUSH, null, "pulse")
                if (result == TextToSpeech.ERROR) {
                    voiceView.text = "Ошибка озвучки. Проверьте настройки голоса."
                }
            } else if (!fresh) {
                tts?.stop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            clearReading("Доступ к пульсу отозван. Разрешите чтение повторно.")
        } catch (e: Exception) {
            clearReading("Не удалось прочитать Health Connect. Повторим через 10 секунд.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("monitoring", monitoring)
        outState.putBoolean("voice", voiceEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        tts?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
