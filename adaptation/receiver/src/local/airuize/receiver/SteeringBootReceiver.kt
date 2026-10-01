package local.airuize.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore only an explicitly enabled listener after reboot; do not start CarPlay. */
class SteeringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).getBoolean("steeringVoice", false)) return
        try { context.startService(Intent(context, SteeringVoiceService::class.java)) }
        catch (error: Exception) { VoiceKeyJournal.add("开机恢复方向盘监听失败：${error.javaClass.simpleName}") }
    }
}
