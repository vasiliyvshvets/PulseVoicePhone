package com.pulsevoicephone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 18f
            setPadding(32, 64, 32, 32)
            text = "Доступ к пульсу\n\nPulseVoicePhone читает сохранённые Samsung Health " +
                "измерения пульса за последние 24 часа через Health Connect, показывает их время " +
                "и озвучивает свежие показания.\n\nДанные обрабатываются на телефоне, " +
                "не отправляются на серверы и не записываются в Health Connect. " +
                "Чтение и озвучка работают, пока открыт главный экран приложения.\n\n" +
                "Доступ можно отозвать в настройках Health Connect."
        })
    }
}
