package com.doudou.pet

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastInteractionTime = System.currentTimeMillis()
    private var lastForegroundApp = ""

    private val greetings = listOf(
        "嘿，我在呢", "戳我干嘛呀", "今天过得怎么样？", "累了就歇会儿吧"
    )
    private val whispers = listOf(
        "记得喝水呀", "坐久了动一动", "今天也要开心一点", "我一直都在这儿",
        "眼睛累了就看看远方", "深呼吸一下吧"
    )

    // === App 检测反应：包名关键字 -> (表情, 台词) ===
    private val appReactions = listOf(
        Triple(listOf("camera"), "surprised", "咔嚓，拍照呢？"),
        Triple(listOf("map", "amap", "baidu.map"), "happy", "要出门啦？路上小心"),
        Triple(listOf("video", "youtube", "bilibili", "iqiyi", "tencentvideo"), "shy", "看剧不要熬夜哦"),
        Triple(listOf("music", "netease.cloudmusic", "kugou"), "wink", "换首歌吧~"),
        Triple(listOf("taobao", "tmall", "jd", "pinduoduo"), "surprised", "又要买买买啦？"),
        Triple(listOf("game", "tencent.tmgp"), "happy", "玩得开心点，别熬夜")
    )

    private var screenshotObserver: ContentObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var usageRunnable: Runnable? = null
    private var whisperRunnable: Runnable? = null
    private var idleRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(whispers.first()))
        showOverlay()
        startScreenshotWatcher()
        startBatteryWatcher()
        startForegroundAppWatcher()
        startWhisperLoop()
        startIdleWatcher()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val channelId = "clawd_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "螃蟹桌宠", NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("小蟹正在陪着你")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ============ 悬浮窗与手势 ============

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl("file:///android_asset/pet.html")
        }

        val sizePx = (150 * resources.displayMetrics.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            sizePx, sizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 40
        layoutParams.y = 300

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                react()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mood("happy", "好开心！")
                markInteraction()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                mood("shy", "嘿嘿，别捏我啦")
                markInteraction()
            }
        })

        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        if (!isDragging) {
                            isDragging = true
                            webView.evaluateJavascript("setDragging(true)", null)
                        }
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(webView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        webView.evaluateJavascript("setDragging(false)", null)
                        markInteraction()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(webView, layoutParams)
    }

    private fun react() {
        mood("wink", greetings.random())
        markInteraction()
    }

    private fun mood(moodName: String, speech: String? = null, speechMs: Int = 2200) {
        webView.evaluateJavascript("setPetMood('$moodName')", null)
        if (speech != null) {
            val escaped = speech.replace("'", "\\'")
            webView.evaluateJavascript("setPetSpeech('$escaped', $speechMs)", null)
        }
    }

    private fun markInteraction() {
        lastInteractionTime = System.currentTimeMillis()
    }

    // ============ 截图检测 ============

    private fun startScreenshotWatcher() {
        val handlerThread = HandlerThread("screenshot-watcher").apply { start() }
        val handler = Handler(handlerThread.looper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri ?: return
                mainHandler.post {
                    mood("surprised", "咔嚓，截图啦？")
                }
            }
        }
        screenshotObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    // ============ 充电检测 ============

    private fun startBatteryWatcher() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> mood("happy", "充电中，谢谢投喂电力")
                    Intent.ACTION_POWER_DISCONNECTED -> mood("neutral", "拔了呀，那我们省着点用")
                }
            }
        }
        batteryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(receiver, filter)
    }

    // ============ 前台 App 检测 ============

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startForegroundAppWatcher() {
        if (!hasUsageAccess()) return // 需要用户在系统设置里手动授权“使用情况访问权限”
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val runnable = object : Runnable {
            override fun run() {
                val end = System.currentTimeMillis()
                val begin = end - 6000
                val events = usageStatsManager.queryEvents(begin, end)
                val event = android.app.usage.UsageEvents.Event()
                var currentApp = lastForegroundApp
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        currentApp = event.packageName
                    }
                }
                if (currentApp.isNotEmpty() && currentApp != lastForegroundApp) {
                    lastForegroundApp = currentApp
                    reactToApp(currentApp)
                }
                mainHandler.postDelayed(this, 4000)
            }
        }
        usageRunnable = runnable
        mainHandler.postDelayed(runnable, 4000)
    }

    private fun reactToApp(packageName: String) {
        val lower = packageName.lowercase()
        for ((keywords, moodName, speech) in appReactions) {
            if (keywords.any { lower.contains(it) }) {
                mood(moodName, speech)
                return
            }
        }
    }

    // ============ 通知栏碎碎念 ============

    private fun startWhisperLoop() {
        val runnable = object : Runnable {
            override fun run() {
                updateNotification(whispers.random())
                mainHandler.postDelayed(this, 60 * 60 * 1000L) // 每小时
            }
        }
        whisperRunnable = runnable
        mainHandler.postDelayed(runnable, 60 * 60 * 1000L)
    }

    // ============ 孤独感知（长时间没互动会变困/委屈） ============

    private fun startIdleWatcher() {
        val runnable = object : Runnable {
            override fun run() {
                val idleMinutes = (System.currentTimeMillis() - lastInteractionTime) / 60000
                when {
                    idleMinutes >= 180 -> mood("shy", "有点想你了，来看看我吧")
                    idleMinutes >= 30 -> mood("sleepy")
                }
                mainHandler.postDelayed(this, 5 * 60 * 1000L) // 每 5 分钟检查一次
            }
        }
        idleRunnable = runnable
        mainHandler.postDelayed(runnable, 5 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized && ::windowManager.isInitialized) {
            windowManager.removeView(webView)
        }
        screenshotObserver?.let { contentResolver.unregisterContentObserver(it) }
        batteryReceiver?.let { unregisterReceiver(it) }
        usageRunnable?.let { mainHandler.removeCallbacks(it) }
        whisperRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.doudou.pet.STOP"
    }
}
