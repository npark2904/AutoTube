package com.personal.andautoutube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout

@SuppressLint("StaticFieldLeak")
object CarWebViewManager {

    private const val TAG = "CarWebViewManager"
    private var webView: WebView? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: AutoPresentation? = null
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDpi = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialLoaded = false
    private var userRequestedPause = false
    var isCarConnected = false
        set(value) {
            val changed = (field != value)
            field = value
            if (changed) {
                mainHandler.post {
                    carConnectionListener?.invoke(value)
                }
            }
        }
    var carConnectionListener: ((Boolean) -> Unit)? = null
    private val resumeRunnable = Runnable {
        if (!isCarConnected || userRequestedPause) return@Runnable
        keepAlive()
        val resumeJs = """
            (function() {
                try {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    var v = document.querySelector('video');
                    if (v && v.paused && !v.ended && v.currentTime > 0) {
                        if (player && typeof player.playVideo === 'function') {
                            player.playVideo();
                        } else {
                            v.play();
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        webView?.evaluateJavascript(resumeJs, null)
    }
    var voiceSearchTrigger: (() -> Unit)? = null
    var voiceSearchCancelTrigger: (() -> Unit)? = null
    private var lastSyncedUrl: String = ""
    private var lastCanonicalKey: String = ""
    private var lastSyncTimestamp: Long = 0L

    /** 동기화 에코(무한 핑퐁) 방지를 위한 방향별 락 플래그 */
    @Volatile var isApplyingCarSyncToPhone: Boolean = false
    @Volatile var isApplyingPhoneSyncToCar: Boolean = false

    @Volatile var currentMediaTitle: String = "Youtube"
    @Volatile var isPlayingMedia: Boolean = false

    fun hasActivePlayer(): Boolean {
        val title = currentMediaTitle.trim()
        val isDefaultTitle = title.isEmpty() || title.equals("Youtube", ignoreCase = true)
        val url = webView?.url ?: ""
        val isWatchUrl = url.contains("/watch") || url.contains("/shorts")
        return (!isDefaultTitle || isWatchUrl)
    }

    var phoneUrlSyncListener: ((String) -> Unit)? = null

    fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.trim().removeSuffix("/")
    }

    /**
     * 유튜브 URL의 핵심 식별자(동영상 ID, 검색어, 홈, 채널 등)를 추출하여 정규 키 생성.
     * app=m, feature, tracking 쿼리나 리다이렉트로 인한 URL 미세 불일치 시 무한 동기화 루프를 원천 차단함.
     */
    fun getCanonicalKey(url: String?): String {
        if (url.isNullOrBlank()) return ""
        try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            if (!host.contains("youtube.com") && !host.contains("youtu.be")) {
                return normalizeUrl(url)
            }

            // youtu.be/VIDEO_ID
            if (host.contains("youtu.be")) {
                val videoId = uri.pathSegments.firstOrNull()?.trim()
                if (!videoId.isNullOrEmpty()) return "video:$videoId"
            }

            val path = uri.path ?: ""

            // /watch?v=VIDEO_ID
            val vParam = uri.getQueryParameter("v")
            if (!vParam.isNullOrBlank()) {
                return "video:${vParam.trim()}"
            }

            // /shorts/VIDEO_ID
            if (path.startsWith("/shorts/")) {
                val shortId = path.removePrefix("/shorts/").split('/', '?', '#').firstOrNull()?.trim()
                if (!shortId.isNullOrEmpty()) return "video:$shortId"
            }

            // /results?search_query=QUERY
            val searchParam = uri.getQueryParameter("search_query") ?: uri.getQueryParameter("q")
            if (!searchParam.isNullOrBlank()) {
                return "search:${searchParam.trim().lowercase()}"
            }

            // /playlist?list=LIST_ID
            val listParam = uri.getQueryParameter("list")
            if (!listParam.isNullOrBlank() && (path.contains("playlist") || path.contains("watch"))) {
                return "playlist:${listParam.trim()}"
            }

            // Home / Explore
            if (path.isEmpty() || path == "/" || path == "/feed/explore" || path == "/feed/trending") {
                return "home"
            }

            return "path:${path.trim().removeSuffix("/")}"
        } catch (_: Exception) {
            return normalizeUrl(url)
        }
    }

    fun getCurrentUrl(): String? = webView?.url ?: lastSyncedUrl.ifEmpty { null }

    /** 안드로이드 오토 WebView에서 URL이 변경되었을 때 모바일 WebView로 동기화 */
    fun onCarUrlChanged(url: String) {
        if (isApplyingPhoneSyncToCar) {
            // 차가 모바일의 동기화 요청을 수행하는 중에 발생한 이벤트는 모바일로 다시 에코하지 않음
            return
        }
        val newKey = getCanonicalKey(url)
        if (newKey.isEmpty()) return
        if (newKey == lastCanonicalKey) return

        val now = System.currentTimeMillis()
        if (now - lastSyncTimestamp < 350L && newKey == lastCanonicalKey) return

        lastCanonicalKey = newKey
        lastSyncedUrl = normalizeUrl(url)
        lastSyncTimestamp = now

        mainHandler.post {
            isApplyingCarSyncToPhone = true
            phoneUrlSyncListener?.invoke(url)
            mainHandler.postDelayed({
                isApplyingCarSyncToPhone = false
            }, 1200L)
        }
    }

    /** 모바일 WebView에서 URL이 변경되었을 때 안드로이드 오토 WebView로 동기화 */
    fun syncUrlFromPhone(url: String) {
        if (isApplyingCarSyncToPhone) {
            // 모바일이 차의 동기화 요청을 수행하는 중에 발생한 이벤트는 차로 다시 에코하지 않음
            return
        }
        val newKey = getCanonicalKey(url)
        if (newKey.isEmpty()) return
        if (newKey == lastCanonicalKey && getCanonicalKey(webView?.url) == newKey) return

        val now = System.currentTimeMillis()
        if (now - lastSyncTimestamp < 350L && newKey == lastCanonicalKey) return

        lastCanonicalKey = newKey
        lastSyncedUrl = normalizeUrl(url)
        lastSyncTimestamp = now

        mainHandler.post {
            presentation?.exitFullscreenIfNeeded()
            if (getCanonicalKey(webView?.url) != newKey) {
                isApplyingPhoneSyncToCar = true
                webView?.loadUrl(url)
                mainHandler.postDelayed({
                    isApplyingPhoneSyncToCar = false
                }, 1200L)
            }
        }
    }

    /**
     * 백그라운드 전환/서피스 분리 시에도 Chromium 엔진이 윈도우 가시성을 항상 VISIBLE로 인식하도록 보장하는 커스텀 WebView
     */
    private class KeepAliveWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(true)
        }
    }

    const val VISIBILITY_SPOOF_JS = """
        (function() {
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);
            } catch (err) {}
            try {
                var FakeSR = function() {};
                FakeSR.prototype.start = function() {
                    if (window.AndroidMedia && window.AndroidMedia.requestVoiceSearch) {
                        window.AndroidMedia.requestVoiceSearch();
                    }
                };
                FakeSR.prototype.stop = function() {};
                FakeSR.prototype.abort = function() {};
                FakeSR.prototype.addEventListener = function() {};
                FakeSR.prototype.removeEventListener = function() {};
                window.SpeechRecognition = FakeSR;
                window.webkitSpeechRecognition = FakeSR;
            } catch (err2) {}
        })();
    """

    /** 마이크 권한 조회(navigator.permissions.query)가 항상 granted를 반환하도록 하여 YouTube의 "브라우저 설정에서 허용" 안내를 방지 */
    const val MIC_PERMISSION_JS = """
        (function() {
            try {
                var origQuery = navigator.permissions && navigator.permissions.query ? navigator.permissions.query.bind(navigator.permissions) : null;
                if (origQuery) {
                    navigator.permissions.query = function(desc) {
                        if (desc && desc.name === 'microphone') {
                            return Promise.resolve({ state: 'granted', onchange: null,
                                addEventListener: function() {}, removeEventListener: function() {} });
                        }
                        return origQuery(desc);
                    };
                }
            } catch (err) {}
            try {
                var FakeSR = function() {};
                FakeSR.prototype.start = function() {
                    if (window.AndroidMedia && window.AndroidMedia.requestVoiceSearch) {
                        window.AndroidMedia.requestVoiceSearch();
                    }
                };
                FakeSR.prototype.stop = function() {};
                FakeSR.prototype.abort = function() {};
                FakeSR.prototype.addEventListener = function() {};
                FakeSR.prototype.removeEventListener = function() {};
                window.SpeechRecognition = FakeSR;
                window.webkitSpeechRecognition = FakeSR;
            } catch (err2) {}
        })();
    """

    /** 화면(서피스)이 사라져도 웹뷰 타이머/미디어가 멈추지 않도록 유지 */
    fun keepAlive() {
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
    }

    /**
     * 백그라운드 전환 직후 Chromium 또는 YouTube 모바일 웹이 일시적으로 비디오를 멈추더라도
     * 사용자가 직접 일시정지한 상태가 아니라면 즉시 재생을 복구함
     */
    fun ensurePlayingInBackground() {
        if (!isCarConnected || userRequestedPause) return
        keepAlive()
        mainHandler.removeCallbacks(resumeRunnable)
        mainHandler.postDelayed(resumeRunnable, 150)
        mainHandler.postDelayed(resumeRunnable, 450)
    }

    /**
     * 안드로이드 오토 서피스에 VirtualDisplay 및 AutoPresentation 연결.
     * 서피스가 재바인딩되거나 새 세션이 시작될 때마다 Presentation/VirtualDisplay를 완전히 새로 생성하여
     * Android ViewRootImpl 누락으로 인한 320x240 크기 축소 버그를 근본적으로 방지함.
     */
    fun attachSurface(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        visibleRect: Rect? = null
    ) {
        try {
            if (!surface.isValid) {
                Log.w(TAG, "attachSurface ignored: surface is already invalid")
                return
            }
            isCarConnected = true

            val existing = virtualDisplay
            val existingPres = presentation
            if (existing != null && existingPres != null &&
                width == lastWidth && height == lastHeight && dpi == lastDpi
            ) {
                // 다른 앱 전환 후 복귀 시: 기존 가상 디스플레이와 프레젠테이션이 유지되고 있으므로 서피스만 재연결 (재로딩/끊김 방지)
                existing.surface = surface
                if (!existingPres.isShowing) {
                    existingPres.show()
                }
                existingPres.ensureWebViewAttached()
                visibleRect?.let { existingPres.updateVisibleArea(it) }
                keepAlive()
                Log.d(TAG, "Surface re-attached to existing VirtualDisplay (playback & WebView preserved)")
                return
            }

            // 가상 디스플레이가 없거나 해상도/DPI가 변경된 경우에만 완전히 해제 후 재생성
            dismissPresentationInternal()

            val appCtx = context.applicationContext
            val displayManager = appCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            virtualDisplay = displayManager.createVirtualDisplay(
                "AutoTubeVirtualDisplay",
                width,
                height,
                dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            lastWidth = width
            lastHeight = height
            lastDpi = dpi

            virtualDisplay?.display?.let { display ->
                presentation = AutoPresentation(appCtx, display, width, height).apply {
                    show()
                    ensureWebViewAttached()
                    visibleRect?.let { rect -> updateVisibleArea(rect) }
                }
                Log.d(TAG, "AutoPresentation successfully displayed on VirtualDisplay (${width}x${height})")
            }
            keepAlive()
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching surface to VirtualDisplay", e)
        }
    }

    private fun getOrCreateDummySurface(width: Int, height: Int): Surface {
        val w = if (width > 0) width else 800
        val h = if (height > 0) height else 400
        val tex = dummySurfaceTexture ?: SurfaceTexture(10).also {
            dummySurfaceTexture = it
        }
        tex.setDefaultBufferSize(w, h)
        val surf = dummySurface
        if (surf == null || !surf.isValid) {
            surf?.release()
            dummySurface = Surface(tex)
        }
        return dummySurface!!
    }

    /**
     * 백그라운드 진입 또는 화면 분할 전환 시 호스트 서피스로부터 안전하게 분리하고
     * 오프스크린 더미 서피스로 전환하여 호스트 파괴의 영향을 차단하고
     * Display.STATE_ON 유지 및 BufferQueue abandoned (-19) 에러 방지
     */
    fun detachSurface() {
        try {
            presentation?.exitFullscreenIfNeeded()
            // 호스트 Surface가 파괴되기 전에 오프스크린 더미 서피스로 안전하게 전환
            val dummy = getOrCreateDummySurface(lastWidth, lastHeight)
            virtualDisplay?.surface = dummy
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching surface", e)
        }
        if (isCarConnected) {
            keepAlive()
        }
    }

    /** 안드로이드 오토(DHU/차량) 세션이 종료될 때 호출: 백그라운드 강제 재생 타이머 취소, 비디오 일시정지, 가상 디스플레이 및 웹뷰 해제 */
    fun onCarDisconnected() {
        Log.d(TAG, "onCarDisconnected: stopping surface & audio playback cleanly")
        isCarConnected = false
        mainHandler.removeCallbacks(resumeRunnable)

        // 1. 가장 먼저 비디오 일시정지 및 오디오 포커스 해제 (닫히고 있는 DHU로 오디오 패킷 유입 원천 차단 -> 0xc0000005 크래시 방지)
        try {
            val pauseJs = """
                (function() {
                    try {
                        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (p && typeof p.pauseVideo === 'function') p.pauseVideo();
                        var v = document.querySelector('video');
                        if (v) v.pause();
                    } catch(e) {}
                })();
            """.trimIndent()
            webView?.evaluateJavascript(pauseJs, null)
        } catch (_: Exception) {}
        AutoMediaBrowserService.onCarDisconnected()

        // 2. 전체화면 해제 및 프레젠테이션/가상디스플레이 안전 해제
        try {
            presentation?.exitFullscreenIfNeeded()
        } catch (_: Exception) {}
        dismissPresentationInternal()

        // 3. 더미 서피스 릴리스
        try {
            dummySurface?.release()
        } catch (_: Exception) {}
        dummySurface = null
        try {
            dummySurfaceTexture?.release()
        } catch (_: Exception) {}
        dummySurfaceTexture = null

        // 4. 웹뷰 리소스 정리
        destroyWebViewInternal()
    }

    fun getPresentation(): AutoPresentation? = presentation

    private fun dismissPresentationInternal() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing presentation", e)
        }
        presentation = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtualDisplay", e)
        }
        virtualDisplay = null
    }

    fun destroyWebViewInternal() {
        try {
            val wv = webView ?: return
            val lastUrl = wv.url
            if (!lastUrl.isNullOrEmpty()) {
                lastSyncedUrl = normalizeUrl(lastUrl)
            }
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying webView", e)
        }
        webView = null
        isInitialLoaded = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context, display: Display? = null): WebView {
        if (webView == null) {
            val targetDisplayId = display?.displayId
            Log.d(TAG, "Creating singleton Car WebView for display: $targetDisplayId")
            val targetContext = if (display != null) {
                context.applicationContext.createDisplayContext(display)
            } else {
                context.applicationContext
            }

            webView = KeepAliveWebView(targetContext).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(true)
                    builtInZoomControls = false
                    displayZoomControls = false

                    // 차량 가로 화면(800x400 / 1024x600)에 맞게 텍스트 및 UI 비율 최적화 (65% 배율)
                    textZoom = 65

                    userAgentString = userAgentString.replace("; wv", "")
                }

                // 적응형 스케일 설정
                setInitialScale(100)
                webViewClient = YouTubeCarWebViewClient(null, null)

                // 음성 검색용 마이크 권한: 앱에 RECORD_AUDIO가 허용돼 있으면 웹 요청을 승인
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                        request ?: return
                        mainHandler.post {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                targetContext, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) request.grant(request.resources) else request.deny()
                        }
                    }
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true

                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onMediaStateChanged(title: String, isPlaying: Boolean) {
                        if (!isCarConnected) return
                        currentMediaTitle = title
                        isPlayingMedia = isPlaying
                        if (isPlaying) userRequestedPause = false
                        AutoMediaBrowserService.updateCurrentMediaFromCar(title, isPlaying)
                    }

                    @android.webkit.JavascriptInterface
                    fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean) {
                        onMediaDetailsChanged(title, artist, isPlaying, "")
                    }

                    @android.webkit.JavascriptInterface
                    fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean, thumbnailUrl: String) {
                        if (!isCarConnected) return
                        currentMediaTitle = title
                        isPlayingMedia = isPlaying
                        if (isPlaying) userRequestedPause = false
                        AutoMediaBrowserService.updateCurrentMediaFromCar(title, isPlaying, artist, thumbnailUrl)
                    }

                    @android.webkit.JavascriptInterface
                    fun requestVoiceSearch() {
                        mainHandler.post { voiceSearchTrigger?.invoke() }
                    }
                }, "AndroidMedia")

                // 백그라운드에서도 유튜브가 '페이지가 숨겨졌다'고 판단해 영상을 일시정지하지 않도록 가시성 API를 항상 visible로 위장
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, VISIBILITY_SPOOF_JS, setOf("*"))
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, MIC_PERMISSION_JS, setOf("*"))
                }
            }
        }

        // 기존 부모 뷰가 있으면 안전하게 분리하여 재사용 가능하도록 준비
        (webView?.parent as? ViewGroup)?.removeView(webView)

        if (!isInitialLoaded) {
            isInitialLoaded = true
            val targetUrl = lastSyncedUrl.ifEmpty { "https://m.youtube.com" }
            presentation?.exitFullscreenIfNeeded()
            webView?.loadUrl(targetUrl)
            onCarUrlChanged(targetUrl)
        }

        return webView!!
    }

    private var isScrubbing = false
    private val scrubCommitRunnable = Runnable {
        endScrubbing()
    }

    fun dispatchTap(x: Float, y: Float) {
        presentation?.dispatchTap(x, y) ?: run {
            if (isScrubbing) {
                endScrubbing()
                return
            }
            val target = webView ?: return
            mainHandler.post {
                try {
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = downTime

                    val downEvent = MotionEvent.obtain(
                        downTime,
                        eventTime,
                        MotionEvent.ACTION_DOWN,
                        x,
                        y,
                        0
                    )
                    target.dispatchTouchEvent(downEvent)
                    downEvent.recycle()

                    mainHandler.postDelayed({
                        val upTime = SystemClock.uptimeMillis()
                        val upEvent = MotionEvent.obtain(
                            downTime,
                            upTime,
                            MotionEvent.ACTION_UP,
                            x,
                            y,
                            0
                        )
                        target.dispatchTouchEvent(upEvent)
                        upEvent.recycle()
                    }, 60)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching tap", e)
                }
            }
        }
    }

    fun dispatchScroll(distanceX: Float, distanceY: Float) {
        presentation?.dispatchScroll(distanceX, distanceY) ?: run {
            val target = webView ?: return
            mainHandler.post {
                try {
                    val absX = Math.abs(distanceX)
                    val absY = Math.abs(distanceY)
                    if (isScrubbing || (absX > absY * 1.2f && absX >= 3.0f)) {
                        if (!isScrubbing) {
                            isScrubbing = true
                            target.evaluateJavascript("if (window.__atScrubStart) window.__atScrubStart();", null)
                        }
                        mainHandler.removeCallbacks(scrubCommitRunnable)
                        mainHandler.postDelayed(scrubCommitRunnable, 1000L)
                        target.evaluateJavascript("if (window.__atScrubDelta) window.__atScrubDelta($distanceX);", null)
                    } else {
                        target.scrollBy(distanceX.toInt(), distanceY.toInt())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching scroll", e)
                }
            }
        }
    }

    fun dispatchFling(velocityX: Float, velocityY: Float) {
        presentation?.dispatchFling(velocityX, velocityY) ?: run {
            if (isScrubbing) {
                endScrubbing()
            }
        }
    }

    private fun endScrubbing() {
        if (!isScrubbing) return
        isScrubbing = false
        mainHandler.removeCallbacks(scrubCommitRunnable)
        webView?.evaluateJavascript("if (window.__atScrubCommit) window.__atScrubCommit();", null)
    }

    fun goBack(): Boolean {
        val target = webView ?: return false
        mainHandler.post {
            if (target.canGoBack()) {
                target.goBack()
            } else {
                val currentUrl = target.url ?: ""
                if (currentUrl.isNotEmpty() &&
                    !currentUrl.endsWith("m.youtube.com/") &&
                    !currentUrl.endsWith("m.youtube.com")) {
                    target.loadUrl("https://m.youtube.com")
                }
            }
        }
        return true
    }

    fun reload() {
        mainHandler.post {
            webView?.reload()
        }
    }

    fun loadUrl(url: String) {
        mainHandler.post {
            presentation?.exitFullscreenIfNeeded()
            if (getCanonicalKey(webView?.url) != getCanonicalKey(url)) {
                webView?.loadUrl(url)
            }
            onCarUrlChanged(url)
        }
    }

    fun loadHome() {
        mainHandler.post {
            val homeUrl = "https://m.youtube.com"
            presentation?.exitFullscreenIfNeeded()
            webView?.loadUrl(homeUrl)
            onCarUrlChanged(homeUrl)
        }
    }

    fun playPauseVideo() {
        mainHandler.post {
            val js = """
                (function() {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.getPlayerState === 'function') {
                        var state = player.getPlayerState();
                        if (state === 1) { player.pauseVideo(); return 'paused'; }
                        else { player.playVideo(); return 'playing'; }
                    } else {
                        var v = document.querySelector('video');
                        if (v) {
                            if (v.paused) { v.play(); return 'playing'; }
                            else { v.pause(); return 'paused'; }
                        }
                    }
                    return '';
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js) { result ->
                if (result?.contains("paused") == true) {
                    userRequestedPause = true
                } else if (result?.contains("playing") == true) {
                    userRequestedPause = false
                }
            }
        }
    }

    fun nextVideo() {
        mainHandler.post {
            val js = """
                (function() {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.nextVideo === 'function') {
                        player.nextVideo();
                    } else {
                        var nextBtn = document.querySelector('.ytp-next-button');
                        if (nextBtn) nextBtn.click();
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }
    }

    fun prevVideo() {
        mainHandler.post {
            val js = """
                (function() {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.previousVideo === 'function') {
                        player.previousVideo();
                    } else {
                        var prevBtn = document.querySelector('.ytp-prev-button');
                        if (prevBtn) prevBtn.click();
                        else history.back();
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }
    }

    fun seekRelative(seconds: Int) {
        mainHandler.post {
            val js = """
                (function() {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.getCurrentTime === 'function' && typeof player.seekTo === 'function') {
                        var cur = player.getCurrentTime();
                        player.seekTo(cur + ($seconds), true);
                    } else {
                        var v = document.querySelector('video');
                        if (v) { v.currentTime += ($seconds); }
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }
    }
}
