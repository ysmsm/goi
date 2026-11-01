package dev.seungmin.goi

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.seungmin.goi.care.CareEngine
import kotlinx.coroutines.MainScope

class GoiApplication : Application() {
    lateinit var engine: CareEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = CareEngine(this, MainScope())
    }
}

/**
 * 모드가 켜진 동안 앱이 화면에서 내려가도 차량 리스너가 끊기지 않도록 프로세스를 유지한다
 * (기획서 리스크 "백그라운드 제한" 대응).
 */
class CareService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notification = Notifier(this).serviceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_SERVICE, notification)
        }
        (application as GoiApplication).engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
