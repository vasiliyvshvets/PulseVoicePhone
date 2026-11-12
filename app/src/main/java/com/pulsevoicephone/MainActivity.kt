package com.pulsevoicephone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var bpmView: TextView
    private lateinit var statusView: TextView
    private lateinit var timeView: TextView
    private lateinit var voiceView: TextView
    private lateinit var toggleButton: Button
    private var voiceEnabled = true
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val allowed = granted.containsAll(SessionAccess.permissions)
        DiagnosticLog.event("session_permission_result all=$allowed")
        statusView.text = if (allowed) "Доступ разрешён. Можно начать сеанс перед пробежкой."
            else "Нужно разрешить чтение пульса и чтение в фоновом режиме."
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        DiagnosticLog.event("notification_permission=$granted")
        launchSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceEnabled = if (PulseSessionService.status.value.running)
            PulseSessionService.status.value.voiceEnabled else savedInstanceState?.getBoolean("voice") ?: true
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun label(value: String, size: Float = 17f): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(10), 0, dp(10))
            content.addView(this)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value
            setOnClickListener { action() }
            content.addView(this)
        }
        label("PulseVoicePhone", 27f)
        label("Озвучка во время пробежки")
        bpmView = label("— уд/мин", 42f)
        timeView = label("Измерений пока нет")
        statusView = label(PulseSessionService.status.value.message)
        button("Разрешить чтение пульса") { requestAccess() }
        button("Настройки Health Connect") {
            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
                open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            else openProvider()
        }
        toggleButton = button("Начать сеанс") {
            if (PulseSessionService.status.value.running) {
                startService(Intent(this, PulseSessionService::class.java).setAction(PulseSessionService.STOP))
            } else prepareSession()
        }
        CheckBox(this).apply {
            text = "Озвучивать по-русски каждые 10 секунд"
            isChecked = voiceEnabled
            setOnCheckedChangeListener { _, checked ->
                voiceEnabled = checked
                if (PulseSessionService.status.value.running) {
                    startService(Intent(this@MainActivity, PulseSessionService::class.java)
                        .setAction(PulseSessionService.SET_VOICE).putExtra("voice", checked))
                }
            }
            content.addView(this)
        }
        voiceView = label(PulseSessionService.status.value.voice)
        button("Диагностика и отчёт") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        button("Настройки голоса") { open(Intent("com.android.settings.TTS_SETTINGS")) }
        button("Настройки батареи приложения") {
            open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        label("Перед пробежкой:\n" +
            "1. На часах: Samsung Health → настройки пульса → непрерывное измерение. " +
            "Путь может отличаться по версии часов.\n" +
            "2. На телефоне: разрешите Samsung Health записывать пульс в Health Connect.\n" +
            "3. Здесь разрешите чтение пульса и фоновое чтение. Нажмите «Начать сеанс» " +
            "и проверьте голос. После этого можно выключить экран.\n" +
            "4. Если телефон ограничивает фоновые приложения, выберите для PulseVoicePhone " +
            "режим батареи «Без ограничений».")
        label("Сеанс продолжает чтение при скрытом приложении. Для остановки используйте " +
            "кнопку здесь или в уведомлении. После перезагрузки/принудительной остановки " +
            "нужно запустить сеанс снова.\n\n" +
            "Новые данные зависят от синхронизации Samsung Health. Показания старше 2 минут " +
            "не озвучиваются. Это чтение сохранённых данных, а не прямое подключение к датчику. " +
            "Если телефон не поддерживает фоновое чтение Health Connect, сеанс недоступен.")
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PulseSessionService.status.collect { state ->
                    toggleButton.text = if (state.running) "Остановить сеанс" else "Начать сеанс"
                    statusView.text = state.message
                    voiceView.text = state.voice
                    bpmView.text = state.sample?.let { "${it.bpm} уд/мин" } ?: "— уд/мин"
                    timeView.text = state.sample?.let { "Измерено: ${formatter.format(it.time)}" }
                        ?: "Нет доступного измерения"
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestAccess() {
        try {
            if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
                openProvider()
                return
            }
            val client = HealthConnectClient.getOrCreate(this)
            if (!SessionAccess.backgroundSupported(client)) {
                statusView.text = "Фоновое чтение Health Connect не поддерживается. Обновите Android/Health Connect."
                return
            }
            healthPermissions.launch(SessionAccess.permissions)
        } catch (e: Exception) {
            DiagnosticLog.event("permission_request_error class=${e.javaClass.name}")
            statusView.text = "Не удалось запросить доступ. Проверьте Health Connect."
        }
    }

    private fun prepareSession() {
        lifecycleScope.launch {
            try {
                if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) {
                    openProvider()
                    return@launch
                }
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                if (!SessionAccess.backgroundSupported(client)) {
                    statusView.text = "Телефон не поддерживает чтение Health Connect с выключенным экраном."
                    return@launch
                }
                if (!client.permissionController.getGrantedPermissions().containsAll(SessionAccess.permissions)) {
                    statusView.text = "Сначала разрешите чтение пульса и чтение в фоне."
                    requestAccess()
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else launchSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.event("session_prepare_error class=${e.javaClass.name}")
                statusView.text = "Не удалось подготовить сеанс. Откройте диагностику."
            }
        }
    }

    private fun launchSession() {
        try {
            startForegroundService(Intent(this, PulseSessionService::class.java)
                .setAction(PulseSessionService.START).putExtra("voice", voiceEnabled))
        } catch (e: Exception) {
            DiagnosticLog.event("session_launch_error class=${e.javaClass.name}")
            statusView.text = "Не удалось запустить фоновую озвучку. Откройте диагностику."
        }
    }

    private fun openProvider() {
        statusView.text = "Установите или обновите Health Connect и вернитесь в приложение."
        open(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (e: ActivityNotFoundException) {
            statusView.text = "Этот раздел недоступен. Откройте его в настройках телефона."
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("voice", voiceEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        DiagnosticLog.event("main_visible session_running=${PulseSessionService.status.value.running}")
    }

    override fun onStop() {
        DiagnosticLog.event("main_hidden session_running=${PulseSessionService.status.value.running}")
        super.onStop()
    }
}
