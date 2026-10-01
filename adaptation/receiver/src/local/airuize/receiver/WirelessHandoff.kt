package local.airuize.receiver

/** Release RFCOMM only after both the phone's request and authenticated tunnel readiness. */
class WirelessHandoff {
    private var requested = false
    private var ready = false
    private var completed = false
    private var failed = false
    @Synchronized fun request(type: String): Boolean {
        if (!type.equals("disableBluetooth", true) && !type.equals("disable-bluetooth", true)) return false
        requested = true
        return true
    }
    @Synchronized fun tunnelReady() { ready = true }
    enum class TimeoutAction { IGNORE, KEEP_VIDEO, FAIL }
    // Mirrors CarPlayController.armWirelessHandoffWatchdog: retain proven video;
    // otherwise fail and dispose the wireless stack after 45 seconds.
    @Synchronized fun timeoutAction(closed: Boolean, renderedFrame: Boolean): TimeoutAction {
        if (closed || !requested || completed || failed || ready) return TimeoutAction.IGNORE
        if (renderedFrame) return TimeoutAction.KEEP_VIDEO
        failed = true
        return TimeoutAction.FAIL
    }
    @Synchronized fun reset() { requested = false; ready = false; completed = false; failed = false }
    @Synchronized fun summary(): String = "交接请求=$requested；Wi-Fi认证就绪=$ready；交接已触发=$completed；交接失败=$failed"
    @Synchronized fun claimCompletion(): Boolean {
        if (!requested || !ready || completed || failed) return false
        completed = true
        return true
    }
}
