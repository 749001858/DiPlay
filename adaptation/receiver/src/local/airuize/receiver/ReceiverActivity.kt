package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.media.*
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import com.shilapi.xcertplay.airplay.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var status: TextView
    private lateinit var microphone: CheckBox
    private lateinit var vehicleGps: CheckBox
    private lateinit var video: SurfaceView
    private lateinit var videoBox: FrameLayout
    private lateinit var controls: LinearLayout
    private var displayWidth = 1024
    private var displayHeight = 600
    private var displayFps = 60
    private var sink: LegacyMediaSink? = null
    @Volatile private var controller: LegacyWirelessController? = null
    @Volatile private var destroyed = false
    private val selfTesting = AtomicBoolean(false)
    private val toneTesting = AtomicBoolean(false)
    private val connectionGeneration = AtomicInteger()
    private var transitioning = false
    private val lifecycleWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "legacy-receiver-lifecycle").apply { isDaemon = true } }
    private val report = StringBuilder("DiPlay CS11 0.14; Android 4.4/API19; experimental\n")
    private val reportStarted = android.os.SystemClock.elapsedRealtime()
    private val diagnosticHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var gpsProbe: LegacyGpsProbe
    private val touchSent = java.util.concurrent.atomic.AtomicLong()
    private val touchFailed = java.util.concurrent.atomic.AtomicLong()
    private val diagnosticTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (controller != null) diagnosticSummary()
            diagnosticHandler.postDelayed(this, 15000)
        }
    }
    private fun diagnosticSummary() {
        VoiceKeyJournal.drain().forEach(::log)
        controller?.let { log(it.diagnosticSummary()) }
        sink?.let { log(it.diagnosticSummary()) }
        log("触摸统计（应用累计）：事件通道写入成功=${touchSent.get()}；失败=${touchFailed.get()}（不证明手机已执行）")
        try {
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            log("系统音频状态：mode=${audio.mode}；音乐音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}；通话音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}；通知音量=${audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)}；麦克风静音=${audio.isMicrophoneMute}；扬声器=${audio.isSpeakerphoneOn}；蓝牙SCO=${audio.isBluetoothScoOn}（仅读取，不修改选路）")
        } catch (error: Exception) { log("系统音频状态读取失败：${error.javaClass.simpleName}") }
    }
    private val touchQueue = ArrayBlockingQueue<Pair<AirPlaySession, List<AirPlayContact>>>(64)
    private var touchThread: Thread? = null
    private fun log(text: String) {
        synchronized(report) {
            report.append('+').append(android.os.SystemClock.elapsedRealtime() - reportStarted).append("ms ").append(text).append('\n')
            if (report.length > 60000) report.delete(0, report.length - 48000)
        }
        if (!destroyed) runOnUiThread { if (!destroyed) status.text = text }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val preferences = getSharedPreferences("receiver-ui", MODE_PRIVATE)
        val savedDisplay = preferences.getInt("displayMode", -1)
        val displayMode = if (savedDisplay >= 0) savedDisplay else if (preferences.getBoolean("lowResolution", false)) 2 else 3
        when (displayMode) {
            0 -> { displayWidth = 1280; displayHeight = 720 }
            2 -> { displayWidth = 800; displayHeight = 480 }
            3 -> { displayWidth = 1920; displayHeight = 1080 }
            else -> { displayWidth = 1024; displayHeight = 600 }
        }
        displayFps = if (preferences.getInt("displayFps", 60) >= 60) 60 else 30
        val layout = FrameLayout(this)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xee202020.toInt()) }
        var toolbar = LinearLayout(this)
        var buttonCount = 0
        controls.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        fun button(label: String, action: () -> Unit) {
            if (buttonCount > 0 && buttonCount % 4 == 0) {
                toolbar = LinearLayout(this)
                controls.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            }
            toolbar.addView(Button(this).apply { text = label; textSize = 14f; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
            buttonCount++
        }
        button("连接 iPhone") { choosePhone() }
        button("停止") { stopReceiver() }
        button("Siri") { requestSiri("菜单") }
        button("分辨率") {
            AlertDialog.Builder(this).setTitle("画面分辨率（重新连接生效）")
                .setSingleChoiceItems(arrayOf("1280×720", "1024×600", "800×480 兼容模式", "1920×1080（高清）"),
                    when (displayWidth) { 1280 -> 0; 800 -> 2; 1920 -> 3; else -> 1 }) { dialog, index ->
                    when (index) {
                        0 -> { displayWidth = 1280; displayHeight = 720 }
                        2 -> { displayWidth = 800; displayHeight = 480 }
                        3 -> { displayWidth = 1920; displayHeight = 1080 }
                        else -> { displayWidth = 1024; displayHeight = 600 }
                    }
                    preferences.edit().putInt("displayMode", index).remove("lowResolution").commit()
                    dialog.dismiss(); log("画质已保存，下次连接生效")
                }.setNegativeButton("取消", null).show()
        }
        button("刷新率") {
            AlertDialog.Builder(this).setTitle("CarPlay画面刷新率（重新连接生效）")
                .setSingleChoiceItems(arrayOf("60Hz / 60fps", "30Hz / 30fps 兼容模式"), if (displayFps == 60) 0 else 1) { dialog, index ->
                    displayFps = if (index == 0) 60 else 30
                    preferences.edit().putInt("displayFps", displayFps).commit()
                    dialog.dismiss(); log("刷新率已保存为 ${displayFps}Hz，下次连接生效")
                }.setNegativeButton("取消", null).show()
        }
        button("音视频自检") { selfTest() }
        button("日志") { showReport() }
        button("导出") { exportReport() }
        button("诊断") { showDiagnostics() }
        microphone = CheckBox(this).apply { text = "麦克风 PCM"; textSize = 12f }
        val inputOptions = LinearLayout(this)
        inputOptions.addView(microphone)
        vehicleGps = CheckBox(this).apply {
            text = "车载GPS（重新连接生效）"; textSize = 12f
            isChecked = preferences.getBoolean("vehicleGps", true)
            setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("vehicleGps", enabled).commit() }
        }
        inputOptions.addView(vehicleGps)
        controls.addView(inputOptions)
        val steering = CheckBox(this).apply {
            text = "领克 CS11 方控（OneOS 后台监听）"; textSize = 12f
            isChecked = preferences.getBoolean("steeringVoice", true)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("steeringVoice", enabled).commit()
                updateSteeringService(enabled)
            }
        }
        controls.addView(steering)
        status = TextView(this).apply { text = "CS11 实验接收端 v0.14：正在准备自动连接。"; textSize = 14f; setTextColor(android.graphics.Color.WHITE) }
        controls.addView(status)
        videoBox = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video = SurfaceView(this)
        video.holder.addCallback(this)
        videoBox.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoBox.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val availableWidth = right - left; val availableHeight = bottom - top
            if (availableWidth > 0 && availableHeight > 0) {
                val width = minOf(availableWidth, availableHeight * displayWidth / displayHeight)
                val height = width * displayHeight / displayWidth
                if (video.layoutParams.width != width || video.layoutParams.height != height)
                    video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        video.setOnTouchListener { view, event ->
            val session = controller?.activeSession
            if (session != null) {
                val action = event.actionMasked
                val contacts = (0 until minOf(event.pointerCount, 2)).map { index ->
                    AirPlayContact(event.getPointerId(index), (event.getX(index) / view.width).toDouble().coerceIn(0.0, 1.0),
                        (event.getY(index) / view.height).toDouble().coerceIn(0.0, 1.0),
                        action != MotionEvent.ACTION_CANCEL && !(index == event.actionIndex && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)))
                }
                // Preserve down/up transitions. Saturation forces a release instead of a stuck contact.
                if (!touchQueue.offer(session to contacts)) { touchQueue.clear(); touchQueue.offer(session to emptyList()) }
            }
            true
        }
        layout.addView(videoBox, FrameLayout.LayoutParams(-1, -1))
        layout.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val quickActions = LinearLayout(this)
        val menu = Button(this).apply {
            text = "菜单 v0.14"; textSize = 13f; setPadding(0, 0, 0, 0)
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        val actionWidth = (100 * resources.displayMetrics.density + 0.5f).toInt()
        val actionHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
        quickActions.addView(menu, LinearLayout.LayoutParams(actionWidth, actionHeight))
        quickActions.addView(Button(this).apply {
            text = "诊断"; textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { showDiagnostics() }
        }, LinearLayout.LayoutParams(actionWidth, actionHeight))
        val menuParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT)
        layout.addView(quickActions, menuParams); setContentView(layout)
        touchThread = Thread({
            while (!destroyed) {
                try {
                    val job = touchQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (job.first.sendTouch(job.second)) touchSent.incrementAndGet() else touchFailed.incrementAndGet()
                }
                catch (_: InterruptedException) { return@Thread } catch (_: Exception) {}
            }
        }, "legacy-touch").apply { isDaemon = true; start() }
        gpsProbe = LegacyGpsProbe(this, ::log)
        log("CS11适配：Android 4.4/API19；i.MX6 H.264；OneOS方控语音/播放暂停/上下曲；音量保留车机原生处理；实车仍需验证")
        diagnosticHandler.postDelayed(diagnosticTick, 15000)
        updateSteeringService(preferences.getBoolean("steeringVoice", true))
        video.post { if (!handleSteeringIntent(intent)) autoConnect() }
    }
    private fun updateSteeringService(enabled: Boolean) {
        getSharedPreferences("receiver-ui", MODE_PRIVATE).edit().putBoolean("steeringVoice", enabled).commit()
        try {
            val service = android.content.Intent(this, SteeringVoiceService::class.java)
            if (enabled) { startService(service); log("CS11 OneOS方控后台监听已请求；可取消勾选关闭") }
            else { stopService(service); log("方向盘后台监听已关闭") }
        } catch (error: Exception) { log("方向盘后台监听异常：${error.javaClass.simpleName}") }
    }
    private fun requestSiri(source: String) {
        val active = controller?.activeSession
        if (active == null) { log("Siri[$source]：没有激活的CarPlay会话"); return }
        Thread {
            try {
                val down = active.sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
                val up = active.sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
                log("Siri[$source]请求：按下写入=$down；释放写入=$up")
            } catch (error: Exception) { log("Siri请求异常：${error.javaClass.simpleName}") }
        }.start()
    }
    private fun handleVoiceKey() {
        VoiceKeyJournal.drain().forEach(::log)
        if (controller?.activeSession != null) requestSiri("方向盘")
        else if (controller == null && !transitioning) { log("语音键启动CarPlay：自动选择记忆手机"); autoConnect() }
        else log("语音键：CarPlay正在连接，请等待")
    }
    private fun handleSteeringAction(action: Int, source: String, keyCode: Int) {
        VoiceKeyJournal.drain().forEach(::log)
        if (action == SteeringKeyPolicy.SIRI) { handleVoiceKey(); return }
        val active = controller?.activeSession
        if (active == null) {
            log("方控${SteeringKeyPolicy.label(action)}[$source/$keyCode]：尚未连接CarPlay，正在启动连接")
            if (controller == null && !transitioning) autoConnect()
            return
        }
        val media = when (action) {
            SteeringKeyPolicy.PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
            SteeringKeyPolicy.NEXT -> CarPlayMediaButton.NEXT
            SteeringKeyPolicy.PREVIOUS -> CarPlayMediaButton.PREVIOUS
            else -> return
        }
        Thread {
            try { active.sendMedia(media); log("方控${SteeringKeyPolicy.label(action)}[$source/$keyCode]已发送") }
            catch (error: Exception) { log("方控发送异常：${error.javaClass.simpleName}") }
        }.start()
    }
    private fun handleSteeringIntent(next: android.content.Intent?): Boolean {
        if (next?.action == SteeringVoiceService.VOICE_ACTION) { handleVoiceKey(); return true }
        if (next?.action != SteeringVoiceService.STEERING_ACTION) return false
        handleSteeringAction(next.getIntExtra(SteeringVoiceService.EXTRA_ACTION, SteeringKeyPolicy.NONE),
            next.getStringExtra(SteeringVoiceService.EXTRA_SOURCE) ?: "未知",
            next.getIntExtra(SteeringVoiceService.EXTRA_KEY_CODE, 0))
        return true
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent)
        handleSteeringIntent(intent)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (android.os.SystemClock.elapsedRealtime() < VoiceKeyJournal.captureUntil)
            log("窗口按键检测：code=${event.keyCode}；action=${event.action}；repeat=${event.repeatCount}")
        val action = SteeringKeyPolicy.fromAndroid(event.keyCode)
        if (action == SteeringKeyPolicy.NONE && !VoiceKeyPolicy.isWindowVoice(event.keyCode)) return super.dispatchKeyEvent(event)
        val resolved = if (action == SteeringKeyPolicy.NONE) SteeringKeyPolicy.SIRI else action
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            SteeringDispatchGate.accept(resolved, android.os.SystemClock.elapsedRealtime()))
            handleSteeringAction(resolved, "窗口按键", event.keyCode)
        return true
    }
    private fun showDiagnostics() {
        val items = arrayOf("功能状态汇总", "车辆GPS检测（60秒）", "导航测试标记", "音频/触摸/Siri结果标记", "声音通道短音测试（先停止连接）", "与原项目功能核对", "方向盘按键检测（15秒）")
        AlertDialog.Builder(this).setTitle("功能诊断 · v0.14 CS11")
            .setItems(items) { _, index -> when (index) {
                0 -> diagnosticSummary()
                1 -> gpsProbe.start()
                2 -> markNavigation()
                3 -> markResults()
                4 -> chooseAudioChannel()
                5 -> showFeatureParity()
                6 -> {
                    VoiceKeyJournal.captureUntil = android.os.SystemClock.elapsedRealtime() + 15000
                    controls.visibility = View.GONE
                    log("CS11方控检测已开始：15秒内依次按语音/播放/上一曲/下一曲；只记录键值，不记录输入文字")
                    diagnosticHandler.postDelayed({ if (!destroyed) { VoiceKeyJournal.drain().forEach(::log); log("方向盘按键检测结束，请导出日志") } }, 15000)
                }
            } }.setNegativeButton("取消", null).show()
    }
    private fun showFeatureParity() {
        try {
            val contents = assets.open("feature-parity.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = TextView(this).apply { this.text = contents; textSize = 16f; setPadding(20, 16, 20, 16) }
            val scroll = ScrollView(this).apply { addView(text) }
            AlertDialog.Builder(this).setTitle("功能核对 · v0.14 CS11")
                .setView(scroll).setPositiveButton("关闭", null).show()
            log("查看功能核对：这是版本能力说明，不是当前会话测试结果")
        } catch (error: Exception) { log("功能核对读取失败：${error.javaClass.simpleName}") }
    }
    private fun markNavigation() {
        val labels = arrayOf("车机停留图标桌面", "准备第一次手机开始导航", "准备在车机打开百度", "已打开车机百度并同步", "百度打开后返回图标桌面", "准备第二次手机开始导航", "导航测试结束")
        AlertDialog.Builder(this).setTitle("记录当前测试步骤（不会启动导航）")
            .setItems(labels) { _, index -> log("用户测试标记：${labels[index]}"); controls.visibility = View.GONE }
            .setNegativeButton("取消", null).show()
    }
    private fun markResults() {
        val labels = arrayOf("音乐有声音", "音乐无声音", "导航播报有声音", "导航播报无声音", "只有音乐有声", "只有播报有声", "声音来自车机扬声器", "声音来自手机", "短音测试听到", "短音测试没听到", "触摸有响应", "触摸无响应", "Siri有响应", "Siri无响应", "通话双向正常", "通话听不到或对方听不到", "画面流畅", "画面卡顿", "打开软件自动连接成功", "打开软件自动连接失败")
        AlertDialog.Builder(this).setTitle("记录人工观察（不会调整音频或车辆设置）")
            .setItems(labels) { _, index -> log("用户结果标记：${labels[index]}"); diagnosticSummary(); controls.visibility = View.GONE }
            .setNegativeButton("取消", null).show()
    }
    private fun chooseAudioChannel() {
        if (controller != null || transitioning || selfTesting.get()) { log("请先停止CarPlay连接并等待释放，再测试声音通道"); return }
        val names = arrayOf("音乐通道 STREAM_MUSIC", "通话通道 STREAM_VOICE_CALL", "通知通道 STREAM_NOTIFICATION")
        val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_NOTIFICATION)
        AlertDialog.Builder(this).setTitle("播放半秒短音，保持当前音量和选路")
            .setItems(names) { _, index -> testAudioChannel(streams[index]) }
            .setNegativeButton("取消", null).show()
    }
    private fun testAudioChannel(stream: Int) {
        if (controller != null || transitioning || selfTesting.get() || !toneTesting.compareAndSet(false, true)) return
        diagnosticSummary()
        Thread({
            var output: AudioTrack? = null
            try {
                val rate = 44100
                val minimum = AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val track = AudioTrack(stream, rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192), AudioTrack.MODE_STREAM)
                output = track; check(track.state == AudioTrack.STATE_INITIALIZED)
                val pcm = ByteArray(rate)
                for (sample in 0 until rate / 2) {
                    val value = (Math.sin(sample * 2.0 * Math.PI * 660.0 / rate) * 1200).toInt()
                    pcm[sample * 2] = value.toByte(); pcm[sample * 2 + 1] = (value shr 8).toByte()
                }
                val manager = getSystemService(AUDIO_SERVICE) as AudioManager
                log("短音测试开始：${AudioDiagnostics.androidStreamName(stream)}($stream)；音量=${manager.getStreamVolume(stream)}/${manager.getStreamMaxVolume(stream)}；44100Hz/单声道/半秒；未修改音量、mode或SCO")
                track.play()
                var bytes = 0
                while (!destroyed && bytes < pcm.size) {
                    val count = track.write(pcm, bytes, minOf(4096, pcm.size - bytes)); check(count > 0); bytes += count
                }
                Thread.sleep(600)
                log("短音测试结果：${AudioDiagnostics.androidStreamName(stream)}($stream)；写入字节=$bytes；请标记听到/没听到及声音来源")
            } catch (error: Exception) { log("短音测试失败：${AudioDiagnostics.androidStreamName(stream)}；${error.javaClass.simpleName}") }
            finally { try { output?.stop() } catch (_: Exception) {}; try { output?.release() } catch (_: Exception) {}; toneTesting.set(false) }
        }, "legacy-channel-test").apply { isDaemon = true; start() }
    }
    private fun autoConnect() {
        if (destroyed || controller != null || selfTesting.get()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) { log("自动连接需要先开启蓝牙"); return }
        val phones = adapter.bondedDevices.toList()
        val saved = getSharedPreferences("receiver-ui", MODE_PRIVATE).getString("phoneAddress", null)
        val remembered = phones.firstOrNull { it.address == saved }
        val candidates = phones.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        val phone = remembered ?: candidates.singleOrNull() ?: phones.singleOrNull()
        if (phone != null) { log("自动连接已保存或唯一的手机"); startPhone(phone) }
        else if (phones.isNotEmpty()) { log("首次使用请选择 iPhone，以后打开应用将自动连接"); choosePhone() }
        else log("请先在车机上配对 iPhone")
    }
    private fun startPhone(phone: BluetoothDevice) {
        if (toneTesting.get()) { log("请等待声音通道短音结束再连接"); return }
        getSharedPreferences("receiver-ui", MODE_PRIVATE).edit().putString("phoneAddress", phone.address).commit()
        controls.visibility = View.VISIBLE
        stopReceiver {
            val seenFrame = AtomicBoolean(false)
            lateinit var next: LegacyWirelessController
            val media = LegacyMediaSink(::log, {
                next.frameRendered()
                if (seenFrame.compareAndSet(false, true)) runOnUiThread {
                    if (!destroyed && controller === next) { controls.visibility = View.GONE; hideSystemBars() }
                }
            }, displayWidth, displayHeight)
            sink = media; if (video.holder.surface.isValid) media.setSurface(video.holder.surface)
            next = LegacyWirelessController(applicationContext, media, ::log, displayWidth, displayHeight, displayFps, vehicleGps.isChecked) { reason ->
                runOnUiThread {
                    if (!destroyed && controller === next) stopReceiver {
                        controls.visibility = View.VISIBLE
                        log("$reason；资源已释放，可点击连接重试")
                    }
                }
            }
            controller = next
            log("请求画面=${displayWidth}×${displayHeight}@${displayFps}Hz；连接后自动收起工具栏")
            log("本次配置：麦克风启用=${microphone.isChecked}；GPS上报启用=${vehicleGps.isChecked}（等待手机订阅）；音频PCM/AAC；视频H264；CS11 OneOS方控已启用；持续重连/轮速挡位未接入")
            next.start(phone, microphone.isChecked)
        }
    }
    private fun choosePhone() {
        if (selfTesting.get()) { log("请等待自检完成"); return }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            check(adapter != null && adapter.isEnabled) { "请先开启蓝牙并配对 iPhone" }
            val phones = adapter.bondedDevices.toList()
            check(phones.isNotEmpty()) { "没有已配对设备" }
            AlertDialog.Builder(this).setTitle("选择已配对的 iPhone（手机保持解锁）")
                .setItems(phones.map { it.name ?: "蓝牙设备" }.toTypedArray()) { _, index ->
                    startPhone(phones[index])
                }.setNegativeButton("取消", null).show()
        } catch (e: Exception) { log(e.message ?: e.javaClass.simpleName) }
    }
    private fun stopReceiver(afterStopped: (() -> Unit)? = null) {
        transitioning = true
        val generation = connectionGeneration.incrementAndGet()
        val old = controller; controller = null
        val media = sink; sink = null; touchQueue.clear()
        if (old != null) log("正在释放旧连接，请等待")
        lifecycleWorker.execute {
            try { old?.close() } finally { media?.close() }
            runOnUiThread {
                if (!destroyed && generation == connectionGeneration.get()) {
                    transitioning = false
                    if (old != null) log("旧连接已释放")
                    afterStopped?.invoke()
                }
            }
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { sink?.setSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { sink?.setSurface(holder.surface) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { sink?.setSurface(null) }
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::controls.isInitialized && controls.visibility != View.VISIBLE) hideSystemBars()
    }
    private fun selfTest() {
        if (toneTesting.get()) { log("请等待声音通道短音结束再自检"); return }
        if (transitioning) { log("请等待旧连接释放完成"); return }
        if (controller != null) { log("请先停止连接，再运行自检"); return }
        if (!video.holder.surface.isValid || !selfTesting.compareAndSet(false, true)) return
        val decoded = AtomicInteger()
        val media = LegacyMediaSink(::log, { decoded.incrementAndGet() })
        sink = media; media.setSurface(video.holder.surface)
        Thread({
            val extractor = MediaExtractor()
            try {
                log("检查加密和上游认证素材")
                val directory = File(filesDir, "offline-mfi").apply { mkdirs() }
                listOf("identity.pk8", "certificate.p7b").forEach { name -> assets.open("offline-mfi/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } } }
                val auth = com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.load(directory)
                check(auth.signChallenge(ByteArray(32) { it.toByte() }).size == 64)
                val key = ByteArray(32) { (it + 1).toByte() }; val nonce = ByteArray(12); val plaintext = "API18 crypto test".toByteArray()
                val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, ByteArray(0))
                check(AirPlayCrypto.chachaOpen(key, nonce, sealed, ByteArray(0)).contentEquals(plaintext))
                log("本地证书/签名/ChaCha 自检通过（未验证 iPhone 信任）")
                val fd = assets.openFd("h264-test.mp4")
                try { extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } finally { fd.close() }
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                fun raw(name: String): ByteArray {
                    val buffer = format.getByteBuffer(name).duplicate(); val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                    val offset = if (bytes.size > 4 && bytes.take(4) == listOf<Byte>(0, 0, 0, 1)) 4 else if (bytes.size > 3 && bytes.take(3) == listOf<Byte>(0, 0, 1)) 3 else 0
                    return bytes.copyOfRange(offset, bytes.size)
                }
                val sps = raw("csd-0"); val pps = raw("csd-1")
                val avcc = byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(), (sps.size shr 8).toByte(), sps.size.toByte()) + sps +
                    byteArrayOf(1, (pps.size shr 8).toByte(), pps.size.toByte()) + pps
                media.onVideoCodec(110, VideoCodec.H264); media.onVideoConfig(110, avcc)
                val buffer = ByteBuffer.allocate(1024 * 1024)
                var count = 0
                while (!destroyed) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                    buffer.position(0); val bytes = ByteArray(size); buffer.get(bytes)
                    media.onVideoFrame(110, bytes); extractor.advance(); count++; Thread.sleep(34)
                }
                Thread.sleep(300)
                log("视频自检送入 $count 帧，已释放显示缓冲 ${decoded.get()} 个；请确认彩色画面")
                val id = AudioStreamId(100, "media")
                val audioFormat = com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.LPCM, 44100, 2, 100)
                media.onAudioStarted(id, audioFormat, 0)
                for (packet in 0 until 50) {
                    if (destroyed) break
                    val bytes = ByteArray(12 + 441 * 4)
                    for (sample in 0 until 441) {
                        val value = (Math.sin((packet * 441 + sample) * 2.0 * Math.PI * 440.0 / 44100) * 1800).toInt()
                        for (channel in 0..1) { val offset = 12 + sample * 4 + channel * 2; bytes[offset] = (value shr 8).toByte(); bytes[offset + 1] = value.toByte() }
                    }
                    media.onAudioRtp(id, audioFormat, bytes, packet * 441); Thread.sleep(10)
                }
                Thread.sleep(300); media.onAudioStopped(id)
                val aac = MediaExtractor()
                try {
                    val audioFd = assets.openFd("aac-test.m4a")
                    try { aac.setDataSource(audioFd.fileDescriptor, audioFd.startOffset, audioFd.length) } finally { audioFd.close() }
                    val audioTrack = (0 until aac.trackCount).first { aac.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                    aac.selectTrack(audioTrack)
                    val aacId = AudioStreamId(102, "media")
                    val aacFormat = com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 102)
                    media.onAudioStarted(aacId, aacFormat, 0)
                    var aacPackets = 0
                    while (!destroyed) {
                        buffer.clear(); val size = aac.readSampleData(buffer, 0); if (size < 0) break
                        val payload = ByteArray(12 + size); buffer.position(0); buffer.get(payload, 12, size)
                        val sample = (aac.sampleTime * 48000L / 1_000_000L).toInt()
                        media.onAudioRtp(aacId, aacFormat, payload, sample)
                        aac.advance(); aacPackets++; Thread.sleep(22)
                    }
                    Thread.sleep(300); media.onAudioStopped(aacId)
                    log("AAC 自检送入 $aacPackets 个音频包，请确认第二段较高提示音")
                } finally { aac.release() }
                log("自检数据发送结束，请确认彩色画面和一秒提示音；这不代表 CarPlay 已连接")
            } catch (e: Exception) { log("自检失败：${e.javaClass.simpleName}") }
            finally { extractor.release(); media.close(); if (sink === media) sink = null; selfTesting.set(false) }
        }, "legacy-self-test").apply { isDaemon = true; start() }
    }
    private fun showReport() {
        val content = synchronized(report) { report.toString() }
        val text = TextView(this).apply { this.text = content; setPadding(12, 12, 12, 12); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle("接收端日志").setView(scroll).setPositiveButton("关闭", null).show()
    }
    private fun exportReport() {
        try {
            val directory = File(Environment.getExternalStorageDirectory(), "DiPlayLegacy").apply { mkdirs() }
            val file = File(directory, "receiver-${System.currentTimeMillis()}.txt")
            file.writeText(synchronized(report) { report.toString() }, Charsets.UTF_8)
            log("日志已保存：${file.absolutePath}")
        } catch (e: Exception) { log("保存失败：${e.javaClass.simpleName}") }
    }
    override fun onDestroy() { if (::gpsProbe.isInitialized) gpsProbe.close(); diagnosticHandler.removeCallbacks(diagnosticTick); destroyed = true; stopReceiver(); lifecycleWorker.shutdown(); touchThread?.interrupt(); super.onDestroy() }
}
