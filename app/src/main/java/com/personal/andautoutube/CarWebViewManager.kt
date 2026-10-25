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
        keepAlive()
        if (userRequestedPause) return
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
        mainHandler.postDelayed({
            keepAlive()
            if (!userRequestedPause) {
                webView?.evaluateJavascript(resumeJs, null)
            }
        }, 120)
        mainHandler.postDelayed({
            keepAlive()
            if (!userRequestedPause) {
                webView?.evaluateJavascript(resumeJs, null)
            }
        }, 400)
    }

    /**
     * 안드로이드 오토 서피스에 VirtualDisplay 및 AutoPresentation 연결.
     * Screen이 finishCarApp() 등으로 재생성되더라도 싱글톤으로 유지하여 백그라운드 재생이 끊기지 않게 보장.
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
            val existing = virtualDisplay
            val existingPres = presentation
            if (existing != null && existingPres != null &&
                width == lastWidth && height == lastHeight && dpi == lastDpi
            ) {
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
     * 백그라운드 진입 또는 화면 분할 전환 시 프레젠테이션(윈도우)은 닫지 않고
     * 오프스크린 더미 서피스로 전환하여 Display.STATE_ON을 유지(SleepToken 방지)하고 백그라운드 재생을 지속함
     */
    fun detachSurface() {
        try {
            virtualDisplay?.surface = getOrCreateDummySurface(lastWidth, lastHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching surface", e)
        }
        keepAlive()
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

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context, display: Display? = null): WebView {
        if (webView == null) {
            Log.d(TAG, "Creating singleton Car WebView")
            // 가상 디스플레이 Context를 사용하여 정확한 해상도/DPI를 물려받음
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
                        if (isPlaying) userRequestedPause = false
                        AutoMediaBrowserService.updateCurrentMedia(title, isPlaying)
                    }

                    @android.webkit.JavascriptInterface
                    fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean) {
                        if (isPlaying) userRequestedPause = false
                        AutoMediaBrowserService.updateCurrentMedia(title, isPlaying, artist)
                    }
                }, "AndroidMedia")

                // 백그라운드에서도 유튜브가 '페이지가 숨겨졌다'고 판단해 영상을 일시정지하지 않도록 가시성 API를 항상 visible로 위장
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, VISIBILITY_SPOOF_JS, setOf("*"))
                }
            }
        }

        // 기존 부모 뷰가 있으면 안전하게 분리하여 재사용 가능하도록 준비
        (webView?.parent as? ViewGroup)?.removeView(webView)

        if (!isInitialLoaded) {
            isInitialLoaded = true
            webView?.loadUrl("https://m.youtube.com")
        }

        return webView!!
    }

    fun dispatchTap(x: Float, y: Float) {
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

                // 실제 손가락 탭 간격(60ms)을 두고 UP 이벤트 전송
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

    fun dispatchScroll(distanceX: Float, distanceY: Float) {
        val target = webView ?: return
        mainHandler.post {
            try {
                // 웹뷰 내부 컨텐츠 스크롤 (배율 감안)
                target.scrollBy(distanceX.toInt(), distanceY.toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching scroll", e)
            }
        }
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
            webView?.loadUrl(url)
        }
    }

    fun loadHome() {
        mainHandler.post {
            webView?.loadUrl("https://m.youtube.com")
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
