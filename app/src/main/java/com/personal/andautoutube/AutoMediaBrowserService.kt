package com.personal.andautoutube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

class AutoMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private var defaultAlbumArt: Bitmap? = null
    private var currentTitle: String = "Youtube"
    private var currentArtist: String = "AutoTube"
    private var currentPlaybackState: Int = PlaybackStateCompat.STATE_PLAYING
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasRequestedInitialAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        android.util.Log.d("AutoMediaBrowserService", "onAudioFocusChange: $focusChange")
    }

    fun requestAudioFocusOnce(): Boolean {
        if (hasRequestedInitialAudioFocus) return true
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audioManager = am
        hasRequestedInitialAudioFocus = true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                    .also { audioFocusRequest = it }
                am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoMediaBrowserService", "Error requesting audio focus", e)
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()

        // 앨범 아트용 기본 아이콘 비트맵 로드 (XML AdaptiveIconDrawable 지원)
        try {
            defaultAlbumArt = getBitmapFromDrawable(this, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 세션 실행 시 열릴 Activity PendingIntent 생성 (Gearhead가 앱 컴포넌트를 정확히 식별하도록 필수)
        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 미디어 세션 생성 (안드로이드 오토 Coolwalk 분할 화면 우측 미디어 카드 및 하단 툴바 위젯 연동)
        mediaSession = MediaSessionCompat(this, "AutoTubeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionActivity(sessionActivityPendingIntent)

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    CarWebViewManager.playPauseVideo()
                }

                override fun onPause() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    CarWebViewManager.playPauseVideo()
                }

                override fun onSkipToNext() {
                    CarWebViewManager.nextVideo()
                }

                override fun onSkipToPrevious() {
                    CarWebViewManager.prevVideo()
                }

                override fun onFastForward() {
                    CarWebViewManager.seekRelative(10)
                }

                override fun onRewind() {
                    CarWebViewManager.seekRelative(-10)
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (!query.isNullOrBlank()) {
                        val url = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query.trim())
                        CarWebViewManager.loadUrl(url)
                    } else {
                        CarWebViewManager.playPauseVideo()
                    }
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    CarWebViewManager.playPauseVideo()
                }
            })

            isActive = true
        }

        if (sessionToken == null) {
            sessionToken = mediaSession.sessionToken
        }

        // 초기 메타데이터 및 재생 상태 설정 (Gearhead의 Invalid metadata 검증 통과용 Display 필드 완비)
        setMediaMetadata(currentTitle, currentArtist)
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

        // 포그라운드 서비스 시작 (백그라운드 생존 및 Coolwalk 미디어 카드 상시 노출)
        startForeground(NOTIFICATION_ID, buildMediaNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                CarWebViewManager.playPauseVideo()
            }
            ACTION_NEXT -> {
                CarWebViewManager.nextVideo()
            }
            ACTION_PREV -> {
                CarWebViewManager.prevVideo()
            }
        }
        // 서비스 재시작 시에도 활성 상태 유지
        updatePlaybackState(currentPlaybackState)
        return START_STICKY
    }

    fun updatePlaybackState(state: Int) {
        currentPlaybackState = state

        if (!hasRequestedInitialAudioFocus && state == PlaybackStateCompat.STATE_PLAYING) {
            requestAudioFocusOnce()
        }

        mediaSession.isActive = true

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            )
            // position을 0L 이상 및 실시간 기준으로 전달하여 안드로이드 오토 미디어 위젯이 활성 상태로 인식하게 함
            .setState(state, 0L, 1.0f, SystemClock.elapsedRealtime())
            .build()

        mediaSession.setPlaybackState(playbackState)

        // 알림 상태도 동기화 갱신
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildMediaNotification())
    }

    fun setMediaMetadata(title: String, artist: String = "AutoTube") {
        val safeTitle = if (title.isBlank()) "YouTube 재생" else title
        val safeArtist = if (artist.isBlank()) "AutoTube" else artist
        currentTitle = safeTitle
        currentArtist = safeArtist

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, safeTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, safeArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "YouTube")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, safeTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, safeArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, safeArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)

        defaultAlbumArt?.let { art ->
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        }

        mediaSession.setMetadata(builder.build())

        // 알림 상태 동기화
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildMediaNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoTube 미디어 재생",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "안드로이드 오토 미디어 세션 및 백그라운드 재생 알림"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildMediaNotification(): Notification {
        val isPlaying = (currentPlaybackState == PlaybackStateCompat.STATE_PLAYING)

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AutoMediaBrowserService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AutoMediaBrowserService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, AutoMediaBrowserService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "이전", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "일시정지" else "재생", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "다음", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        defaultAlbumArt?.let {
            builder.setLargeIcon(it)
        }

        return builder.build()
    }

    private fun getBitmapFromDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, drawableId) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 256
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 256
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (parentId == MEDIA_ROOT_ID) {
            val descriptionBuilder = MediaDescriptionCompat.Builder()
                .setMediaId("youtube_main")
                .setTitle(currentTitle)
                .setSubtitle(currentArtist)
                .setDescription("YouTube 미디어 스트림")

            defaultAlbumArt?.let {
                descriptionBuilder.setIconBitmap(it)
            }

            items.add(
                MediaBrowserCompat.MediaItem(
                    descriptionBuilder.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }
        result.sendResult(items)
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val MEDIA_ROOT_ID = "auto_tube_root"
        private const val CHANNEL_ID = "auto_tube_media_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_PLAY_PAUSE = "com.personal.andautoutube.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.personal.andautoutube.ACTION_NEXT"
        const val ACTION_PREV = "com.personal.andautoutube.ACTION_PREV"

        var instance: AutoMediaBrowserService? = null
        private var isDisplayingOnScreen: Boolean = false

        fun onCarDisconnected() {
            isDisplayingOnScreen = false
            instance?.let { service ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        service.audioFocusRequest?.let { service.audioManager?.abandonAudioFocusRequest(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        service.audioManager?.abandonAudioFocus(service.audioFocusListener)
                    }
                    service.hasRequestedInitialAudioFocus = false
                } catch (_: Exception) {}
                service.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        fun setForegroundDisplaying(displaying: Boolean) {
            isDisplayingOnScreen = displaying
            instance?.let { service ->
                // 미디어 세션은 항상 활성화 상태로 유지하여 Gearhead가 최우선 미디어 카드로 인식하게 함
                // 주의: 여기서 requestAudioFocus()를 재호출하면 같은 앱 내부의 WebView(Chromium)가 가진 오디오 포커스를 뺏어
                // WebView에 AUDIOFOCUS_LOSS(-1)가 전달되고 백그라운드 영상이 일시정지되므로 절대 재요청하지 않음!
                service.mediaSession.isActive = true
            }
        }

        fun updateCurrentMedia(title: String, isPlaying: Boolean = true, artist: String = "AutoTube") {
            instance?.let { service ->
                val newState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                android.util.Log.d("AutoMediaTitle", "title='$title' artist='$artist' playing=$isPlaying")
                val cleanTitle = title.trim()
                val cleanArtist = if (artist.isBlank()) "AutoTube" else artist.trim()
                val metaChanged = cleanTitle.isNotBlank() && (cleanTitle != service.currentTitle || cleanArtist != service.currentArtist)
                val stateChanged = newState != service.currentPlaybackState

                if (metaChanged) {
                    service.setMediaMetadata(cleanTitle, cleanArtist)
                }
                if (stateChanged) {
                    service.updatePlaybackState(newState)
                }
                service.mediaSession.isActive = true
            }
        }
    }
}
