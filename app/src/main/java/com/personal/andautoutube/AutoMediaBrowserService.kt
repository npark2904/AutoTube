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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.HttpURLConnection
import java.net.URL

class AutoMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private var defaultAlbumArt: Bitmap? = null
    private var currentAlbumArt: Bitmap? = null
    private var currentThumbnailUrl: String = ""
    private var currentTitle: String = "YouTube"
    private var currentArtist: String = "AutoTube"
    private var currentPlaybackState: Int = PlaybackStateCompat.STATE_PAUSED
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasRequestedInitialAudioFocus = false
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // 앨범 아트용 기본 아이콘 비트맵 로드
        try {
            defaultAlbumArt = getBitmapFromDrawable(this, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 미디어 세션 생성 (안드로이드 오토 Coolwalk 분할 화면 우측 미디어 카드 및 하단 툴바 위젯 연동)
        // 주의: setSessionActivity를 설정하지 않음 (하단 바 탭 시 '휴대전화에서 열림' 팝업 및 모바일 ANR 방지)
        mediaSession = MediaSessionCompat(this, "AutoTubeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    dispatchPlayPause()
                }

                override fun onPause() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    dispatchPlayPause()
                }

                override fun onSkipToNext() {
                    dispatchNext()
                }

                override fun onSkipToPrevious() {
                    dispatchPrev()
                }

                override fun onFastForward() {
                    if (CarWebViewManager.isCarConnected) {
                        CarWebViewManager.seekRelative(10)
                    }
                }

                override fun onRewind() {
                    if (CarWebViewManager.isCarConnected) {
                        CarWebViewManager.seekRelative(-10)
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (!query.isNullOrBlank()) {
                        val url = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query.trim())
                        if (CarWebViewManager.isCarConnected) {
                            CarWebViewManager.loadUrl(url)
                        }
                    } else {
                        dispatchPlayPause()
                    }
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    dispatchPlayPause()
                }
            })

            isActive = true
        }

        if (sessionToken == null) {
            sessionToken = mediaSession.sessionToken
        }

        // 초기 메타데이터 및 재생 상태: 시작 시에는 항상 PAUSED 상태로 대기 (재생 전에는 스와이프로 닫기 가능)
        setMediaMetadata(currentTitle, currentArtist, null)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)

        // 포그라운드 서비스 시작 후 일시정지 상태이므로 클리어 가능하도록 detach
        startForeground(NOTIFICATION_ID, buildMediaNotification())
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                dispatchPlayPause()
            }
            ACTION_NEXT -> {
                dispatchNext()
            }
            ACTION_PREV -> {
                dispatchPrev()
            }
        }
        return START_STICKY
    }

    fun updatePlaybackState(state: Int, force: Boolean = false) {
        if (!force && currentPlaybackState == state) {
            return
        }
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
            .setState(state, 0L, 1.0f, SystemClock.elapsedRealtime())
            .build()

        mediaSession.setPlaybackState(playbackState)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            // 재생 중: 포그라운드 서비스로 승격하여 백그라운드 종료 방지 및 ongoing=true 유지
            startForeground(NOTIFICATION_ID, buildMediaNotification())
        } else {
            // 일시정지 중: 포그라운드 고정을 해제하여 사용자가 알림을 스와이프로 언제든 닫을 수 있도록 허용
            stopForeground(STOP_FOREGROUND_DETACH)
            notificationManager?.notify(NOTIFICATION_ID, buildMediaNotification())
        }
    }

    fun setMediaMetadata(
        title: String,
        artist: String = "AutoTube",
        art: Bitmap? = null,
        force: Boolean = false
    ) {
        val safeTitle = if (title.isBlank()) "YouTube" else title.trim()
        val safeArtist = if (artist.isBlank()) "AutoTube" else artist.trim()
        val titleChanged = safeTitle != currentTitle
        val artistChanged = safeArtist != currentArtist
        val artChanged = art != null && art != currentAlbumArt

        if (!force && !titleChanged && !artistChanged && !artChanged) {
            return
        }

        currentTitle = safeTitle
        currentArtist = safeArtist
        if (art != null) {
            currentAlbumArt = art
        }

        val activeArt = currentAlbumArt ?: defaultAlbumArt

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, safeTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, safeArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "YouTube")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, safeTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, safeArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, safeArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)

        activeArt?.let { bitmap ->
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
        }

        mediaSession.setMetadata(builder.build())

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildMediaNotification())
    }

    private fun loadThumbnailAsync(thumbnailUrl: String) {
        if (thumbnailUrl.isBlank() || thumbnailUrl == currentThumbnailUrl) return
        currentThumbnailUrl = thumbnailUrl

        Thread {
            try {
                val url = URL(thumbnailUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 3000
                connection.readTimeout = 4000
                connection.connect()
                val input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                input.close()
                if (bitmap != null) {
                    mainHandler.post {
                        if (currentThumbnailUrl == thumbnailUrl) {
                            setMediaMetadata(currentTitle, currentArtist, bitmap)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
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

        val activeArt = currentAlbumArt ?: defaultAlbumArt
        activeArt?.let {
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
                .setDescription("영상 시청은 안드로이드 오토 런처의 '오토튜브' 앱을 터치하세요")

            val activeArt = currentAlbumArt ?: defaultAlbumArt
            activeArt?.let {
                descriptionBuilder.setIconBitmap(it)
            }

            items.add(
                MediaBrowserCompat.MediaItem(
                    descriptionBuilder.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )

            val guideDesc = MediaDescriptionCompat.Builder()
                .setMediaId("guide_screen_info")
                .setTitle("▶ 영상 화면 시청 안내")
                .setSubtitle("안드로이드 오토 런처의 '오토튜브'를 터치하세요")
                .setDescription("오토튜브 앱을 터치하면 전체화면 YouTube 웹뷰가 실행됩니다")
            defaultAlbumArt?.let { guideDesc.setIconBitmap(it) }
            items.add(
                MediaBrowserCompat.MediaItem(
                    guideDesc.build(),
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

        const val SOURCE_NONE = 0
        const val SOURCE_CAR = 1
        const val SOURCE_MOBILE = 2

        var instance: AutoMediaBrowserService? = null
        private var isDisplayingOnScreen: Boolean = false
        private var activeSource: Int = SOURCE_NONE

        // 모바일 단독 실행 시 재생/일시정지/다음/이전 제어 리스너
        var mobileMediaActionListener: (() -> Unit)? = null
        var mobileNextListener: (() -> Unit)? = null
        var mobilePrevListener: (() -> Unit)? = null

        fun dispatchPlayPause() {
            if (CarWebViewManager.isCarConnected) {
                CarWebViewManager.playPauseVideo()
            } else {
                mobileMediaActionListener?.invoke()
            }
        }

        fun dispatchNext() {
            if (CarWebViewManager.isCarConnected) {
                CarWebViewManager.nextVideo()
            } else {
                mobileNextListener?.invoke()
            }
        }

        fun dispatchPrev() {
            if (CarWebViewManager.isCarConnected) {
                CarWebViewManager.prevVideo()
            } else {
                mobilePrevListener?.invoke()
            }
        }

        fun onCarDisconnected() {
            isDisplayingOnScreen = false
            activeSource = SOURCE_NONE
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

        fun stopServiceIfIdle() {
            instance?.let { service ->
                if (service.currentPlaybackState != PlaybackStateCompat.STATE_PLAYING && !CarWebViewManager.isCarConnected) {
                    service.stopForeground(STOP_FOREGROUND_REMOVE)
                    val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(NOTIFICATION_ID)
                    service.stopSelf()
                }
            }
        }

        fun setForegroundDisplaying(displaying: Boolean) {
            isDisplayingOnScreen = displaying
            instance?.let { service ->
                service.mediaSession.isActive = true
            }
        }

        /**
         * 안드로이드 오토(차량) 웹뷰에서 미디어 상태 업데이트를 전달할 때 호출.
         * 차량이 연결되어 있지 않으면 즉시 무시됨.
         */
        fun updateCurrentMediaFromCar(
            title: String,
            isPlaying: Boolean = true,
            artist: String = "AutoTube",
            thumbnailUrl: String? = null
        ) {
            if (!CarWebViewManager.isCarConnected) {
                return
            }
            applyMediaUpdate(SOURCE_CAR, title, isPlaying, artist, thumbnailUrl)
        }

        /**
         * 모바일 화면 웹뷰에서 미디어 상태 업데이트를 전달할 때 호출.
         * 안드로이드 오토가 연결되어 있는 경우 모바일의 상태는 철저히 무시되고 오직 차량 상태만 유지됨.
         */
        fun updateCurrentMediaFromMobile(
            title: String,
            isPlaying: Boolean = true,
            artist: String = "AutoTube",
            thumbnailUrl: String? = null
        ) {
            if (CarWebViewManager.isCarConnected) {
                return
            }
            applyMediaUpdate(SOURCE_MOBILE, title, isPlaying, artist, thumbnailUrl)
        }

        fun updateCurrentMedia(
            title: String,
            isPlaying: Boolean = true,
            artist: String = "AutoTube",
            thumbnailUrl: String? = null
        ) {
            if (CarWebViewManager.isCarConnected) {
                updateCurrentMediaFromCar(title, isPlaying, artist, thumbnailUrl)
            } else {
                updateCurrentMediaFromMobile(title, isPlaying, artist, thumbnailUrl)
            }
        }

        private fun applyMediaUpdate(
            source: Int,
            title: String,
            isPlaying: Boolean,
            artist: String,
            thumbnailUrl: String?
        ) {
            instance?.let { service ->
                val newState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                val cleanTitle = if (title.isBlank()) "YouTube" else title.trim()
                val cleanArtist = if (artist.isBlank()) "AutoTube" else artist.trim()
                val cleanThumb = thumbnailUrl?.trim() ?: ""

                val sourceChanged = (activeSource != source)
                activeSource = source

                val titleChanged = (cleanTitle != service.currentTitle)
                val artistChanged = (cleanArtist != service.currentArtist)
                val stateChanged = (newState != service.currentPlaybackState)
                val thumbChanged = (cleanThumb.isNotBlank() && cleanThumb != service.currentThumbnailUrl)

                // 아무것도 변경되지 않았고 소스 전환도 없다면 즉시 리턴하여 UI 깜빡임 방지
                if (!sourceChanged && !titleChanged && !artistChanged && !stateChanged && !thumbChanged) {
                    return
                }

                if (titleChanged || artistChanged || sourceChanged) {
                    if (thumbChanged || cleanThumb.isBlank()) {
                        service.currentAlbumArt = null
                        if (cleanThumb.isBlank()) {
                            service.currentThumbnailUrl = ""
                        }
                    }
                    service.setMediaMetadata(cleanTitle, cleanArtist, service.currentAlbumArt)
                }

                if (stateChanged || sourceChanged) {
                    service.updatePlaybackState(newState)
                }

                if (thumbChanged) {
                    service.loadThumbnailAsync(cleanThumb)
                }
            }
        }
    }
}
