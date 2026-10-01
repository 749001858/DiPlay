package local.airuize.receiver

object VoiceKeyJournal {
    val gate = VoiceKeyGate()
    private val entries = java.util.ArrayDeque<String>()
    @Volatile var captureUntil = 0L
    @Synchronized fun add(line: String) {
        if (entries.size >= 64) entries.removeFirst()
        entries.addLast(line)
    }
    @Synchronized fun drain(): List<String> {
        val lines = entries.toList(); entries.clear(); return lines
    }
}
