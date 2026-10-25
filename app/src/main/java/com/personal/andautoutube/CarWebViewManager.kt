package com.personal.andautoutube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialLoaded = false

    private const val VISIBILITY_SPOOF_JS = """
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

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context, display: Display? = null): WebView {
        if (webView == null) {
            Log.d(TAG, "Creating singleton Car WebView")
            // 가상 디스플레이 Context를 사용하여 정확한 해상도/DPI를 물려받음
            val targetContext = if (display != null) {
                context.createDisplayContext(display)
            } else {
                context.applicationContext
            }

            webView = WebView(targetContext).apply {
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
                        AutoMediaBrowserService.updateCurrentMedia(title, isPlaying)
                    }

                    @android.webkit.JavascriptInterface
                    fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean) {
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
        if (target.canGoBack()) {
            mainHandler.post { target.goBack() }
            return true
        }
        return false
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
                        if (state === 1) player.pauseVideo();
                        else player.playVideo();
                    } else {
                        var v = document.querySelector('video');
                        if (v) { v.paused ? v.play() : v.pause(); }
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
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
