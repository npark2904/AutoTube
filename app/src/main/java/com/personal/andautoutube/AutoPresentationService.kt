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

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
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

        // 백그라운드(차량 화면 사용 중)에서도 마이크 입력이 무음 처리되지 않도록 microphone 타입 포함
        // (RECORD_AUDIO 허용 시에만; 미허용 상태에서 지정하면 SecurityException 발생)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (micGranted) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.w("AutoPresentationService", "mic FGS type failed, fallback", e)
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_LOAD_URL -> {
                    val url = intent.getStringExtra(EXTRA_URL)
                    if (!url.isNullOrEmpty()) {
                        CarWebViewManager.loadUrl(url)
                    }
                }
                ACTION_PLAY_PAUSE -> {
                    CarWebViewManager.playPauseVideo()
                }
                ACTION_NEXT -> {
                    CarWebViewManager.nextVideo()
                }
                ACTION_PREV -> {
                    CarWebViewManager.prevVideo()
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
