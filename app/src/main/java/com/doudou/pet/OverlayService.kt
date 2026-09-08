package com.doudou.pet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastInteractionTime = System.currentTimeMillis()

    private val greetings = listOf(
        "嘿，我在呢", "戳我干嘛呀", "今天过得怎么样？", "累了就歇会儿吧"
    )
    private val whispers = listOf(
        "记得喝水呀", "坐久了动一动", "今天也要开心一点", "我一直都在这儿",
        "眼睛累了就看看远方", "深呼吸一下吧"
    )

    private var screenshotObserver: ContentObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var whisperRunnable: Runnable? = null
    private var idleRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(whispers.first()))
        showOverlay()
        startScreenshotWatcher()
        startBatteryWatcher()
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

    // ============ 悬浮窗 ============

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl("file:///android_asset/pet.html")
        }

        // 调小尺寸：80dp 宽 x 55dp 高
        val widthPx = (80 * resources.displayMetrics.density).toInt()
        val heightPx = (55 * resources.displayMetrics.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            widthPx, heightPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 40
        layoutParams.y = 300

        // 注入 PetBridge JS 接口
        webView.addJavascriptInterface(PetBridge(), "PetBridge")

        // 手势：把所有触摸交给 pet.html 处理，Kotlin 只管窗口拖动
        webView.setOnTouchListener { _, event ->
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
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(webView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        markInteraction()
                        webView.evaluateJavascript("window.petBridge && window.petBridge.onReturned && window.petBridge.onReturned()", null)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(webView, layoutParams)
    }

    inner class PetBridge {
        @JavascriptInterface
        fun moveTo(x: Int, y: Int) {
            mainHandler.post {
                layoutParams.x = x
                layoutParams.y = y
                windowManager.updateViewLayout(webView, layoutParams)
            }
        }

        @JavascriptInterface
        fun moveBy(dx: Int, dy: Int) {
            mainHandler.post {
                layoutParams.x += dx
                layoutParams.y += dy
                windowManager.updateViewLayout(webView, layoutParams)
            }
        }

        @JavascriptInterface
        fun onTap() {
            mainHandler.post { markInteraction() }
        }

        @JavascriptInterface
        fun onLongPress() {
            mainHandler.post { markInteraction() }
        }
    }

    private fun markInteraction() {
        lastInteractionTime = System.currentTimeMillis()
    }

    private fun startScreenshotWatcher() {
        val handlerThread = HandlerThread("screenshot-watcher").apply { start() }
        val handler = Handler(handlerThread.looper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri ?: return
                mainHandler.post {
                    webView.evaluateJavascript("window.petBridge && window.petBridge.onScreenshot && window.petBridge.onScreenshot()", null)
                }
            }
        }
        screenshotObserver = observer
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    private fun startBatteryWatcher() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        webView.evaluateJavascript("window.petBridge && window.petBridge.onCharging && window.petBridge.onCharging(true)", null)
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        webView.evaluateJavascript("window.petBridge && window.petBridge.onCharging && window.petBridge.onCharging(false)", null)
                    }
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

    private fun startWhisperLoop() {
        val runnable = object : Runnable {
            override fun run() {
                updateNotification(whispers.random())
                mainHandler.postDelayed(this, 60 * 60 * 1000L)
            }
        }
        whisperRunnable = runnable
        mainHandler.postDelayed(runnable, 60 * 60 * 1000L)
    }

    private fun startIdleWatcher() {
        val runnable = object : Runnable {
            override fun run() {
                val idleMinutes = (System.currentTimeMillis() - lastInteractionTime) / 60000
                if (idleMinutes >= 180) {
                    webView.evaluateJavascript("window.petBridge && window.petBridge.onTimeGreeting && window.petBridge.onTimeGreeting('有点想你了，来看看我吧')", null)
                } else if (idleMinutes >= 30) {
                    webView.evaluateJavascript("window.petBridge && window.petBridge.onBatteryLow && window.petBridge.onBatteryLow()", null)
                }
                mainHandler.postDelayed(this, 5 * 60 * 1000L)
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
        whisperRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.doudou.pet.STOP"
    }
}