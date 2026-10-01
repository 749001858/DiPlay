package local.airuize.receiver

/** Codes verified in this firmware's MCUKeyDefine; do not reuse BYD codes. */
object VoiceKeyPolicy {
    fun isMcuVoice(raw: Int): Boolean = raw in -128..255 && (raw and 255) in listOf(93, 221, 118)
    // KeyService adds 1000 to an unmapped signed byte; long voice 221 is -35.
    fun isWindowVoice(code: Int): Boolean = code in listOf(219, 231, 1093, 965, 1221, 1118)
}
class VoiceKeyGate {
    private var last = -1L
    @Synchronized fun accept(now: Long): Boolean {
        if (now < 0 || (last >= 0 && now - last < 600)) return false
        last = now
        return true
    }
}
