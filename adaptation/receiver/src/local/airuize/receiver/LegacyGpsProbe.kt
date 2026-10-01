package local.airuize.receiver

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

/** Foreground, bounded read-only location test. No coordinates or NMEA enter logs. */
class LegacyGpsProbe(context: Context, private val log: (String) -> Unit) : Closeable {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var callbacks = 0
    private var freshGps = 0
    private val finish = Runnable { stop(true) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            callbacks++
            val age = ageMillis(location)
            if (GpsDiagnostics.freshGps(location.provider, location.latitude, location.longitude, location.isFromMockProvider, age)) freshGps++
            if (callbacks <= 3 || callbacks % 10 == 0) describe("更新", location)
        }
        override fun onProviderEnabled(provider: String) { if (running) log("GPS检测：定位源启用=${label(provider)}") }
        override fun onProviderDisabled(provider: String) { if (running) log("GPS检测：定位源停用=${label(provider)}") }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) { if (running) log("GPS检测：定位源=${label(provider)}；status=$status") }
    }
    fun start() {
        if (running) { log("GPS检测正在运行，请等待60秒结束"); return }
        val locations = manager ?: run { log("GPS检测：LocationManager不可用"); return }
        running = true; callbacks = 0; freshGps = 0
        log("GPS检测开始：60秒，仅读取车机定位，不向iPhone上报，不记录经纬度")
        var subscribed = 0
        try {
            val providers = locations.allProviders
            log("GPS检测：定位源数量=${providers.size}；gps存在=${providers.contains(LocationManager.GPS_PROVIDER)}")
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (provider !in providers) continue
                try {
                    log("GPS检测：${label(provider)}启用=${locations.isProviderEnabled(provider)}")
                    locations.getLastKnownLocation(provider)?.let { describe("缓存（不算实时通过）", it) }
                    locations.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    subscribed++
                } catch (error: Exception) { log("GPS检测：${label(provider)}订阅失败=${error.javaClass.simpleName}") }
            }
        } catch (error: Exception) { log("GPS检测：初始化失败=${error.javaClass.simpleName}") }
        if (subscribed == 0) stop(true) else handler.postDelayed(finish, 60000)
    }
    private fun describe(stage: String, location: Location) {
        log("GPS检测$stage：" + GpsDiagnostics.describe(location.provider, ageMillis(location),
            if (location.hasAccuracy()) location.accuracy else null, location.isFromMockProvider, location.hasSpeed(), location.hasBearing()))
    }
    private fun ageMillis(location: Location): Long {
        val fixTime = location.elapsedRealtimeNanos
        return if (fixTime > 0) (SystemClock.elapsedRealtimeNanos() - fixTime) / 1_000_000 else -1
    }
    private fun label(provider: String?): String = when (provider) {
        LocationManager.GPS_PROVIDER -> "gps"
        LocationManager.NETWORK_PROVIDER -> "network"
        else -> "其他"
    }
    private fun stop(completed: Boolean) {
        if (!running) return
        running = false; handler.removeCallbacks(finish)
        try { manager?.removeUpdates(listener) } catch (error: Exception) { log("GPS检测停止：${error.javaClass.simpleName}") }
        log("GPS检测${if (completed) "结束" else "取消"}：实时回调=$callbacks；有效新鲜非模拟gps=$freshGps；${if (freshGps > 0) "普通APK取得实时GPS，尚未验证iPhone使用" else "未取得符合条件的实时GPS，不能据此断定没有硬件"}")
    }
    override fun close() { stop(false) }
}
