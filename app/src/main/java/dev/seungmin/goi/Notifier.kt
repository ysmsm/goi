package dev.seungmin.goi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.care.ForgetAlert

/**
 * 고이가 화면에 없을 때 쓰는 System notification. 제안(켜기/괜찮아요)과 하차 알림(확인)만 보낸다.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "모드 제안·하차 알림", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "모드 실행 중", NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun showSuggestion(mode: CareMode, placeName: String?) {
        val n = builder(CHANNEL_ALERT)
            .setContentTitle("${mode.label} 모드를 켤까요?")
            .setContentText(placeName ?: "고이")
            .addAction(Notification.Action.Builder(null, "괜찮아요", action(ACTION_DISMISS)).build())
            .addAction(Notification.Action.Builder(null, "켜기", action(ACTION_ACCEPT)).build())
            .setTimeoutAfter(10_000)
            .build()
        manager.notify(ID_SUGGESTION, n)
    }

    fun cancelSuggestion() = manager.cancel(ID_SUGGESTION)

    fun showForget(alert: ForgetAlert) {
        val n = builder(CHANNEL_ALERT)
            .setContentTitle(alert.headline)
            .setContentText("내리기 전에 한 번 더 확인해 주세요")
            .addAction(Notification.Action.Builder(null, "확인했어요", action(ACTION_FORGET_OK)).build())
            .setOngoing(true)
            .build()
        manager.notify(ID_FORGET, n)
    }

    fun cancelForget() = manager.cancel(ID_FORGET)

    fun serviceNotification(): Notification = builder(CHANNEL_SERVICE)
        .setContentTitle("고이")
        .setContentText("모드가 켜져 있어요")
        .setOngoing(true)
        .build()

    private fun builder(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    private fun action(action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ALERT = "goi_alert"
        const val CHANNEL_SERVICE = "goi_service"
        const val ID_SERVICE = 1
        const val ID_SUGGESTION = 2
        const val ID_FORGET = 3
        const val ACTION_ACCEPT = "dev.seungmin.goi.ACCEPT"
        const val ACTION_DISMISS = "dev.seungmin.goi.DISMISS"
        const val ACTION_FORGET_OK = "dev.seungmin.goi.FORGET_OK"
    }
}

/** 알림 버튼 처리. 켜기를 누르면 고이를 창에 띄운다. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = (context.applicationContext as GoiApplication).engine
        when (intent.action) {
            Notifier.ACTION_ACCEPT -> {
                engine.acceptSuggestion()
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Notifier.ACTION_DISMISS -> engine.dismissSuggestion()
            Notifier.ACTION_FORGET_OK -> engine.confirmForget()
        }
    }
}
