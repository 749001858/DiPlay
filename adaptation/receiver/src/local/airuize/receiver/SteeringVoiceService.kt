package local.airuize.receiver

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock

/**
 * CS11 steering-wheel bridge. It first uses Geely OneOS' input service and keeps the
 * Android media-button plus older Skypine callback paths as non-invasive fallbacks.
 * It only registers listeners; it never sends CAN/MCU commands or intercepts volume keys.
 */
class SteeringVoiceService : Service() {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var oneOsBound = false
    private var oneOsReady = false
    private var oneOsInput: IBinder? = null
    private var skypineRemote: IBinder? = null
    private lateinit var mediaButtonComponent: ComponentName

    private val oneOsCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(ONEOS_CALLBACK); return true }
            try {
                data.enforceInterface(ONEOS_CALLBACK)
                when (code) {
                    1 -> {
                        val key = data.readInt(); val event = data.readInt(); val function = data.readInt()
                        capture("CS11 OneOS按键：code=$key；event=$event；function=$function")
                        if (event == 1) dispatchOneOs(key, "OneOS抬起")
                    }
                    2 -> {
                        val key = data.readInt(); val function = data.readInt()
                        capture("CS11 OneOS短按：code=$key；function=$function")
                        dispatchOneOs(key, "OneOS短按")
                    }
                    3, 4, 5, 6 -> {
                        val key = data.readInt(); val function = data.readInt()
                        capture("CS11 OneOS扩展事件：type=$code；code=$key；function=$function")
                        if (code == 5 && SteeringKeyPolicy.fromOneOs(key) == SteeringKeyPolicy.SIRI)
                            dispatchOneOs(key, "OneOS长按")
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
                reply?.writeNoException()
                return true
            } catch (error: Exception) {
                VoiceKeyJournal.add("CS11 OneOS回调异常：${error.javaClass.simpleName}")
                return false
            }
        }
    }

    private val skypineCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(SKYPINE_CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(SKYPINE_CALLBACK)
            val raw = data.readInt()
            capture("兼容MCU按键：原始值=$raw；语音键=${VoiceKeyPolicy.isMcuVoice(raw)}")
            if (VoiceKeyPolicy.isMcuVoice(raw)) dispatch(SteeringKeyPolicy.SIRI, "兼容MCU", raw)
            reply?.writeNoException()
            return true
        }
    }

    private val oneOsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Thread({ registerOneOs(service) }, "cs11-oneos-register").apply { isDaemon = true; start() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { oneOsReady = false; oneOsInput = null }
            VoiceKeyJournal.add("CS11 OneOS方控服务已断开，等待系统自动重连")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(13, Notification.Builder(this).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DiPlay · 领克 CS11 方控")
            .setContentText("语音、上一曲、下一曲、播放/暂停").setContentIntent(launch).setOngoing(true).build())
        mediaButtonComponent = ComponentName(this, LegacyMediaButtonReceiver::class.java)
        try {
            (getSystemService(AUDIO_SERVICE) as AudioManager).registerMediaButtonEventReceiver(mediaButtonComponent)
            VoiceKeyJournal.add("CS11方控：Android媒体键后备已注册")
        } catch (error: Exception) { VoiceKeyJournal.add("媒体键后备注册失败：${error.javaClass.simpleName}") }
        bindOneOs()
        handler.postDelayed({ synchronized(lock) { if (!stopped && !oneOsReady) registerSkypineFallback() } }, 3000)
    }

    private fun bindOneOs() {
        if (stopped) return
        synchronized(lock) { if (oneOsBound || oneOsReady) return }
        try {
            val intent = Intent().setClassName(ONEOS_PACKAGE, ONEOS_SERVICE)
            val bound = bindService(intent, oneOsConnection, Context.BIND_AUTO_CREATE)
            synchronized(lock) { oneOsBound = bound }
            VoiceKeyJournal.add(if (bound) "CS11 OneOS方控：正在连接" else "CS11 OneOS方控：服务不存在，启用兼容后备")
        } catch (error: Exception) {
            VoiceKeyJournal.add("CS11 OneOS绑定失败：${error.javaClass.simpleName}；启用兼容后备")
        }
    }

    private fun registerOneOs(manager: IBinder) {
        try {
            val input = transactForBinder(manager, ONEOS_MANAGER, 2, 8)
                ?: throw IllegalStateException("input service missing")
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ONEOS_INPUT)
                data.writeStrongBinder(oneOsCallback)
                data.writeString(packageName)
                data.writeIntArray(SteeringKeyPolicy.oneOsKeys)
                check(input.transact(3, data, reply, 0))
                reply.readException()
            } finally { reply.recycle(); data.recycle() }
            synchronized(lock) { if (stopped) return; oneOsInput = input; oneOsReady = true }
            VoiceKeyJournal.add("CS11 OneOS方控注册成功：语音/播放/上下曲")
        } catch (error: Exception) {
            VoiceKeyJournal.add("CS11 OneOS方控注册失败：${error.javaClass.simpleName}；启用兼容后备")
            synchronized(lock) { if (!stopped) registerSkypineFallback() }
        }
    }

    private fun transactForBinder(remote: IBinder, descriptor: String, code: Int, argument: Int): IBinder? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor); data.writeInt(argument)
            check(remote.transact(code, data, reply, 0)); reply.readException(); reply.readStrongBinder()
        } finally { reply.recycle(); data.recycle() }
    }

    private fun dispatchOneOs(key: Int, source: String) = dispatch(SteeringKeyPolicy.fromOneOs(key), source, key)

    private fun dispatch(action: Int, source: String, key: Int) {
        if (!getSharedPreferences("receiver-ui", MODE_PRIVATE).getBoolean("steeringVoice", true)) return
        if (!SteeringDispatchGate.accept(action, SystemClock.elapsedRealtime())) return
        VoiceKeyJournal.add("方向盘${SteeringKeyPolicy.label(action)}：source=$source；code=$key")
        openReceiver(this, action, source, key)
    }

    private fun capture(line: String) {
        if (SystemClock.elapsedRealtime() < VoiceKeyJournal.captureUntil) VoiceKeyJournal.add(line)
    }

    private fun registerSkypineFallback() {
        if (stopped || skypineRemote != null) return
        try {
            val manager = Class.forName("android.os.ServiceManager")
            val binder = manager.getMethod("getService", String::class.java).invoke(null, "skypine_keyservice") as? IBinder
                ?: throw IllegalStateException()
            check(binder.interfaceDescriptor == SKYPINE_INTERFACE)
            transactSkypine(binder, 2)
            skypineRemote = binder
            VoiceKeyJournal.add("兼容MCU语音键后备注册成功")
        } catch (error: Exception) {
            VoiceKeyJournal.add("兼容MCU后备不可用：${error.javaClass.simpleName}；仍保留Android媒体键")
        }
    }

    private fun transactSkypine(binder: IBinder, code: Int) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SKYPINE_INTERFACE); data.writeStrongBinder(skypineCallback)
            check(binder.transact(code, data, reply, 0)); reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }

    private fun unregisterOneOs() {
        val input = synchronized(lock) { oneOsInput.also { oneOsInput = null; oneOsReady = false } } ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ONEOS_INPUT); data.writeStrongBinder(oneOsCallback); data.writeString(packageName)
            if (input.transact(4, data, reply, 0)) reply.readException()
        } catch (error: Exception) { VoiceKeyJournal.add("CS11 OneOS注销异常：${error.javaClass.simpleName}") }
        finally { reply.recycle(); data.recycle() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!getSharedPreferences("receiver-ui", MODE_PRIVATE).getBoolean("steeringVoice", true)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(lock) { stopped = true }
        handler.removeCallbacksAndMessages(null)
        unregisterOneOs()
        if (oneOsBound) try { unbindService(oneOsConnection) } catch (_: Exception) {}
        oneOsBound = false
        skypineRemote?.let { try { transactSkypine(it, 3) } catch (_: Exception) {} }
        skypineRemote = null
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterMediaButtonEventReceiver(mediaButtonComponent) } catch (_: Exception) {}
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val VOICE_ACTION = "local.airuize.diplaylegacy.STEERING_VOICE"
        const val STEERING_ACTION = "local.airuize.diplaylegacy.STEERING_ACTION"
        const val EXTRA_ACTION = "steering_action"
        const val EXTRA_SOURCE = "steering_source"
        const val EXTRA_KEY_CODE = "steering_key_code"

        private const val ONEOS_PACKAGE = "com.geely.service.oneosapi"
        private const val ONEOS_SERVICE = "com.geely.service.oneosapi.OneOSApiService"
        private const val ONEOS_MANAGER = "com.geely.lib.oneosapi.IServiceManager"
        private const val ONEOS_INPUT = "com.geely.lib.oneosapi.input.IInputManager"
        private const val ONEOS_CALLBACK = "com.geely.lib.oneosapi.input.IInputListener"
        private const val SKYPINE_INTERFACE = "android.app.skypine.mcukey.IKeyService"
        private const val SKYPINE_CALLBACK = "android.app.skypine.mcukey.IKeyCallBack"

        fun openReceiver(context: Context, action: Int, source: String, keyCode: Int) {
            if (action == SteeringKeyPolicy.NONE) return
            try {
                context.startActivity(Intent(context, ReceiverActivity::class.java)
                    .setAction(STEERING_ACTION)
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_KEY_CODE, keyCode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (error: Exception) { VoiceKeyJournal.add("方控打开DiPlay失败：${error.javaClass.simpleName}") }
        }
    }
}
