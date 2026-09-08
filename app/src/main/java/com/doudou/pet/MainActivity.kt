package com.doudou.pet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var usageButton: Button
    private var overlayRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        usageButton = findViewById(R.id.usageButton)

        toggleButton.setOnClickListener { onToggleClicked() }
        usageButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun onToggleClicked() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        if (!overlayRunning) {
            startService(Intent(this, OverlayService::class.java))
            overlayRunning = true
            toggleButton.text = "关闭悬浮桌宠"
            statusText.text = "小蟹已经出现在桌面上啦，去别的 App 看看吧"
        } else {
            stopService(Intent(this, OverlayService::class.java))
            overlayRunning = false
            toggleButton.text = "开启悬浮桌宠"
            statusText.text = "小蟹先去打盹了"
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        statusText.text = "请在接下来的设置页面里，允许小蟹桌宠显示悬浮窗"
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        if (hasOverlayPermission() && !overlayRunning) {
            toggleButton.text = "开启悬浮桌宠"
        }
        usageButton.text = if (hasUsageAccess()) "✓ 前台App检测已授权" else "授权前台App检测（可选）"
    }
}
