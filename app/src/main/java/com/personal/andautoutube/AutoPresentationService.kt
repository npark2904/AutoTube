package com.personal.andautoutube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat

class AutoPresentationService : Service() {

    private lateinit var displayManager: DisplayManager
    private var currentPresentation: AutoPresentation? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId)
            Log.d(TAG, "Display added: id=$displayId, name=${display?.name}")
            checkAndShowPresentation(display)
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: id=$displayId")
            if (currentPresentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        startForegroundNotification()
        scanExistingDisplays()
    }

    private fun startForegroundNotification() {
        val channelId = "auto_tube_channel"
        val channelName = "AutoTube Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoTube 플레이어 실행 중")
            .setContentText("안드로이드 오토 디스플레이 연결 대기 중...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun scanExistingDisplays() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        for (display in displays) {
            if (checkAndShowPresentation(display)) {
                break
            }
        }
    }

    private fun checkAndShowPresentation(display: Display?): Boolean {
        if (display == null || !display.isValid) return false

        val displayName = display.name ?: ""
        // AutoTubeVirtualDisplay는 YouTubeCarScreen이 단독 관리하므로 Service에서 중복 생성하지 않음!
        if (displayName.contains("GhostActivityDisplay", ignoreCase = true) ||
            displayName.contains("AutoTubeVirtualDisplay", ignoreCase = true)) {
            Log.d(TAG, "Skipping display managed by Screen: $displayName")
            return false
        }

        // 기본 폰 화면이 아닌 안드로이드 오토 디스플레이인 경우 Presentation 생성
        if (display.displayId != Display.DEFAULT_DISPLAY) {
            try {
                dismissPresentation()
                Log.d(TAG, "Showing AutoPresentation on display: id=${display.displayId}, name=$displayName")

                currentPresentation = AutoPresentation(this, display).apply {
                    show()
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying presentation on $displayName", e)
            }
        }
        return false
    }

    private fun dismissPresentation() {
        currentPresentation?.let {
            try {
                it.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing presentation", e)
            }
            currentPresentation = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_LOAD_URL -> {
                    val url = intent.getStringExtra(EXTRA_URL)
                    if (!url.isNullOrEmpty()) {
                        currentPresentation?.loadUrl(url)
                    }
                }
                ACTION_PLAY_PAUSE -> {
                    currentPresentation?.playPauseVideo()
                }
                ACTION_NEXT -> {
                    currentPresentation?.nextVideo()
                }
                ACTION_PREV -> {
                    currentPresentation?.prevVideo()
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AutoPresentationService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_LOAD_URL = "com.personal.andautoutube.ACTION_LOAD_URL"
        const val ACTION_PLAY_PAUSE = "com.personal.andautoutube.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.personal.andautoutube.ACTION_NEXT"
        const val ACTION_PREV = "com.personal.andautoutube.ACTION_PREV"
        const val EXTRA_URL = "extra_url"

        fun start(context: Context) {
            val intent = Intent(context, AutoPresentationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
