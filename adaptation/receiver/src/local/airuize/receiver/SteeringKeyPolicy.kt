package local.airuize.receiver

import android.view.KeyEvent

/** Lynk & Co / Geely OneOS wheel key codes used by the CS11 head unit. */
object SteeringKeyPolicy {
    const val NONE = 0
    const val SIRI = 1
    const val PLAY_PAUSE = 2
    const val NEXT = 3
    const val PREVIOUS = 4

    const val ONEOS_VOICE = 200231
    const val ONEOS_PLAY_PAUSE = 200085
    const val ONEOS_NEXT = 200087
    const val ONEOS_PREVIOUS = 200088

    val oneOsKeys = intArrayOf(ONEOS_VOICE, ONEOS_PLAY_PAUSE, ONEOS_NEXT, ONEOS_PREVIOUS)

    fun fromOneOs(keyCode: Int): Int = when (keyCode) {
        ONEOS_VOICE -> SIRI
        ONEOS_PLAY_PAUSE -> PLAY_PAUSE
        ONEOS_NEXT -> NEXT
        ONEOS_PREVIOUS -> PREVIOUS
        else -> NONE
    }

    fun fromAndroid(keyCode: Int): Int = when (keyCode) {
        231, KeyEvent.KEYCODE_SEARCH, ONEOS_VOICE -> SIRI // VOICE_ASSIST was added to the SDK constants after API19
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, ONEOS_PLAY_PAUSE -> PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT, ONEOS_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, ONEOS_PREVIOUS -> PREVIOUS
        else -> NONE
    }

    fun label(action: Int): String = when (action) {
        SIRI -> "Siri"
        PLAY_PAUSE -> "播放/暂停"
        NEXT -> "下一曲"
        PREVIOUS -> "上一曲"
        else -> "未知"
    }
}

/** Drops duplicate delivery when OneOS and Android both report the same physical press. */
object SteeringDispatchGate {
    private val last = HashMap<Int, Long>()
    @Synchronized fun accept(action: Int, now: Long): Boolean {
        if (action == SteeringKeyPolicy.NONE || now < 0) return false
        val previous = last[action]
        if (previous != null && now - previous in 0..249) return false
        last[action] = now
        return true
    }
}
