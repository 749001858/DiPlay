package local.airuize.receiver

object GpsDiagnostics {
    fun freshGps(provider: String?, latitude: Double, longitude: Double, mock: Boolean, ageMillis: Long): Boolean =
        provider == "gps" && latitude in -90.0..90.0 && longitude in -180.0..180.0 && !mock && ageMillis in 0..10000
    fun describe(provider: String?, ageMillis: Long, accuracy: Float?, mock: Boolean, speed: Boolean, bearing: Boolean): String {
        val source = when (provider) { "gps", "network" -> provider; else -> "其他" }
        val precision = accuracy?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0 }?.toInt()?.toString() ?: "未知"
        return "source=$source；ageMs=$ageMillis；精度米=$precision；模拟来源=$mock；有速度=$speed；有方向=$bearing"
    }
}
