package local.airuize.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent

/** API19 media-button fallback for firmware that exposes wheel keys as Android KeyEvents. */
class LegacyMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (!context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
                .getBoolean("steeringVoice", true)) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val action = SteeringKeyPolicy.fromAndroid(event.keyCode)
        if (!SteeringDispatchGate.accept(action, SystemClock.elapsedRealtime())) return
        SteeringVoiceService.openReceiver(context, action, "Android媒体键", event.keyCode)
        abortBroadcast()
    }
}
