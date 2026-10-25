package com.personal.andautoutube

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar

class AutoPresentation(
    outerContext: Context,
    display: Display,
    private val targetWidth: Int = 0,
    private val targetHeight: Int = 0
) : Presentation(outerContext, display, R.style.Theme_AutoTube_Presentation) {

    private lateinit var presentationContainer: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var topMiniControlBar: LinearLayout
    private lateinit var btnFloatingBack: ImageButton
    private lateinit var btnMiniHome: ImageButton
    private lateinit var btnMiniRefresh: ImageButton
    private lateinit var voiceSearchOverlay: LinearLayout
    private lateinit var voiceMicBadge: FrameLayout
    private lateinit var tvVoiceStatus: android.widget.TextView
    private lateinit var tvVoiceSubText: android.widget.TextView
    private val voiceOverlayHideRunnable = Runnable { hideVoiceOverlay() }
    private lateinit var btnMiniRewind: android.widget.Button
    private lateinit var btnMiniForward: android.widget.Button

    private lateinit var scrubOverlay: LinearLayout
    private lateinit var tvScrubAction: android.widget.TextView
    private lateinit var tvScrubTargetTime: android.widget.TextView
    private lateinit var tvScrubDuration: android.widget.TextView
    private lateinit var tvScrubHint: android.widget.TextView

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        topMiniControlBar.animate()
            .alpha(0f).setDuration(350).withEndAction { topMiniControlBar.visibility = View.GONE }
            .start()
        if (::btnFloatingBack.isInitialized) {
            btnFloatingBack.animate()
                .alpha(0f).setDuration(350).withEndAction { btnFloatingBack.visibility = View.GONE }
                .start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_auto)

        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        val w = if (targetWidth > 0) targetWidth else dm.widthPixels
        val h = if (targetHeight > 0) targetHeight else dm.heightPixels

        window?.apply {
            setLayout(w, h)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        presentationContainer = findViewById(R.id.presentationContainer)
        webViewContainer = findViewById(R.id.webViewContainer)
        progressBar = findViewById(R.id.progressBar)
        topMiniControlBar = findViewById(R.id.topMiniControlBar)
        btnFloatingBack = findViewById(R.id.btnFloatingBack)
        btnMiniHome = findViewById(R.id.btnMiniHome)
        btnMiniRefresh = findViewById(R.id.btnMiniRefresh)

        voiceSearchOverlay = findViewById(R.id.voiceSearchOverlay)
        voiceMicBadge = findViewById(R.id.voiceMicBadge)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvVoiceSubText = findViewById(R.id.tvVoiceSubText)
        voiceSearchOverlay.setOnClickListener {
            CarWebViewManager.voiceSearchCancelTrigger?.invoke()
            hideVoiceOverlay()
        }
        btnMiniRewind = findViewById(R.id.btnMiniRewind)
        btnMiniForward = findViewById(R.id.btnMiniForward)

        scrubOverlay = findViewById(R.id.scrubOverlay)
        tvScrubAction = findViewById(R.id.tvScrubAction)
        tvScrubTargetTime = findViewById(R.id.tvScrubTargetTime)
        tvScrubDuration = findViewById(R.id.tvScrubDuration)
        tvScrubHint = findViewById(R.id.tvScrubHint)

        setupMiniControlBar()
        attachWebView()
    }

    override fun onStart() {
        super.onStart()
        // 가상 디스플레이의 실제 픽셀 크기로 윈도우를 명시 고정 (밀도 혼선/MATCH_PARENT 무시 방지)
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        val w = if (targetWidth > 0) targetWidth else dm.widthPixels
        val h = if (targetHeight > 0) targetHeight else dm.heightPixels

        window?.apply {
            setLayout(w, h)
            val lp = attributes
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.FILL
            attributes = lp
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        window?.decorView?.post {
            Log.d("AutoPresentation", "display=${dm.widthPixels}x${dm.heightPixels} dpi=${dm.densityDpi}, target=${w}x${h}, " +
                "decor=${window?.decorView?.width}x${window?.decorView?.height}, " +
                "container=${webViewContainer.width}x${webViewContainer.height}, " +
                "webView=${webView?.width}x${webView?.height}, " +
                "ctxDensityDpi=${context.resources.displayMetrics.densityDpi}")
        }
    }

    private fun setupMiniControlBar() {
        btnFloatingBack.setOnClickListener {
            if (customView != null) {
                // 전체화면 재생 중이면 전체화면 먼저 해제
                (customView?.parent as? ViewGroup)?.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            } else {
                CarWebViewManager.goBack()
            }
            showMiniControlBar(1500L)
        }

        btnMiniRewind.setOnClickListener {
            seekBy(-5)
            showMiniControlBar(1500L)
        }
        btnMiniForward.setOnClickListener {
            seekBy(5)
            showMiniControlBar(1500L)
        }

        btnMiniHome.setOnClickListener {
            exitFullscreenIfNeeded()
            CarWebViewManager.loadHome()
            showMiniControlBar(1500L)
        }

        btnMiniRefresh.setOnClickListener {
            exitFullscreenIfNeeded()
            CarWebViewManager.reload()
            showMiniControlBar(1500L)
        }

        // 최초 진입 시 상단 툴바와 원형 백버튼을 3초간 선명하게 띄운 뒤 자동 숨김 전환
        topMiniControlBar.alpha = 1f
        topMiniControlBar.visibility = View.VISIBLE
        btnFloatingBack.alpha = 1f
        btnFloatingBack.visibility = View.VISIBLE
        showMiniControlBar(3000L)
    }

    /** ESC 키 이벤트 발생 (네이티브 KeyEvent + 웹 keydown/keyup 이벤트) */
    private fun sendEscape() {
        if (customView != null) {
            (customView?.parent as? ViewGroup)?.removeView(customView)
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            topMiniControlBar.bringToFront()
        }
        val wv = webView ?: return
        wv.evaluateJavascript("try { if (document.fullscreenElement) document.exitFullscreen(); else if (document.webkitFullscreenElement) document.webkitExitFullscreen(); } catch(e){}", null)
        val now = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE, 0))
        wv.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ESCAPE, 0))
        val js = "['keydown','keyup'].forEach(function(t){var e=new KeyboardEvent(t,{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});(document.activeElement||document.body).dispatchEvent(e);document.dispatchEvent(e);});"
        wv.evaluateJavascript(js, null)
    }

    /** 영상을 초 단위로 앞/뒤 이동 (모바일 웹은 단축키가 없어 video.currentTime 직접 조작) */
    private fun seekBy(seconds: Int) {
        val js = "var v=document.querySelector('video'); if(v){ v.currentTime=Math.max(0,Math.min(v.duration||1e9, v.currentTime+($seconds))); }"
        webView?.evaluateJavascript(js, null)
    }

    private var micPulseAnimator: android.animation.ObjectAnimator? = null

    fun showVoiceOverlay(
        status: String,
        subText: String = "검색할 단어를 말씀해 주세요 (터치 시 취소)",
        autoHideMs: Long = 0L
    ) {
        if (!::voiceSearchOverlay.isInitialized) return
        hideHandler.removeCallbacks(voiceOverlayHideRunnable)
        tvVoiceStatus.text = status
        tvVoiceSubText.text = subText
        voiceSearchOverlay.bringToFront()
        voiceSearchOverlay.translationZ = 200f
        voiceSearchOverlay.animate().cancel()
        voiceSearchOverlay.alpha = 1f
        voiceSearchOverlay.visibility = View.VISIBLE
        if (autoHideMs == 0L) {
            if (micPulseAnimator == null) {
                val scaleX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.12f)
                val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.12f)
                micPulseAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(voiceMicBadge, scaleX, scaleY).apply {
                    duration = 750L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
            }
            if (micPulseAnimator?.isRunning != true) {
                micPulseAnimator?.start()
            }
        } else {
            micPulseAnimator?.cancel()
            voiceMicBadge.scaleX = 1f
            voiceMicBadge.scaleY = 1f
            hideHandler.postDelayed(voiceOverlayHideRunnable, autoHideMs)
        }
    }

    fun hideVoiceOverlay() {
        if (!::voiceSearchOverlay.isInitialized) return
        hideHandler.removeCallbacks(voiceOverlayHideRunnable)
        micPulseAnimator?.cancel()
        voiceMicBadge.scaleX = 1f
        voiceMicBadge.scaleY = 1f
        voiceSearchOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { voiceSearchOverlay.visibility = View.GONE }
            .start()
    }

    fun showMiniControlBar(durationMs: Long = 2500L) {
        hideHandler.removeCallbacks(hideRunnable)
        topMiniControlBar.bringToFront()
        topMiniControlBar.translationZ = 100f
        topMiniControlBar.animate().cancel()
        topMiniControlBar.alpha = 1f
        topMiniControlBar.visibility = View.VISIBLE

        if (::btnFloatingBack.isInitialized) {
            btnFloatingBack.bringToFront()
            btnFloatingBack.translationZ = 100f
            btnFloatingBack.animate().cancel()
            btnFloatingBack.alpha = 1f
            btnFloatingBack.visibility = View.VISIBLE
        }

        hideHandler.postDelayed(hideRunnable, durationMs)
    }

    /** 웹뷰는 항상 100% 전체화면을 유지하고, 플로팅 툴바(좌측 상단)와 원형 Back 버튼(좌측 하단)을 가시 영역에 맞춰 오버레이 표시 */
    fun updateVisibleArea(visibleArea: Rect) {
        if (visibleArea.width() <= 0 || visibleArea.height() <= 0) return

        // 웹뷰 컨테이너는 별도 영역 분리 없이 전체 화면(MATCH_PARENT) 고정
        val lp = webViewContainer.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                    lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                    lp.leftMargin != 0 || lp.topMargin != 0)) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
            webViewContainer.layoutParams = lp
        }

        // 플로팅 툴바(좌측 상단)와 원형 Back 버튼(좌측 하단)을 가시 영역에 맞춰 오버레이로 배치
        val density = context.resources.displayMetrics.density
        val marginPx = (16 * density).toInt()

        val barLp = topMiniControlBar.layoutParams as? FrameLayout.LayoutParams
        barLp?.let {
            it.gravity = Gravity.START or Gravity.TOP
            it.leftMargin = visibleArea.left + marginPx
            it.topMargin = visibleArea.top + marginPx
            it.rightMargin = 0
            it.bottomMargin = 0
            topMiniControlBar.layoutParams = it
        }

        if (::btnFloatingBack.isInitialized) {
            val backLp = btnFloatingBack.layoutParams as? FrameLayout.LayoutParams
            backLp?.let {
                it.gravity = Gravity.START or Gravity.BOTTOM
                it.leftMargin = visibleArea.left + marginPx
                val screenHeight = if (targetHeight > 0) targetHeight else context.resources.displayMetrics.heightPixels
                val bottomOffset = Math.max(0, screenHeight - visibleArea.bottom)
                it.bottomMargin = bottomOffset + marginPx
                it.rightMargin = 0
                it.topMargin = 0
                btnFloatingBack.layoutParams = it
            }
        }
    }

    enum class ScrollMode {
        IDLE,
        VERTICAL,
        HORIZONTAL_SCRUB
    }

    private var currentScrollMode = ScrollMode.IDLE
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f

    private val verticalScrollResetRunnable = Runnable {
        if (currentScrollMode == ScrollMode.VERTICAL) {
            currentScrollMode = ScrollMode.IDLE
            pendingDeltaX = 0f
            pendingDeltaY = 0f
        }
    }

    private var isScrubbing = false
    private var scrubInitialTime: Double = 0.0
    private var scrubTargetTime: Double = 0.0
    private var scrubDuration: Double = 0.0
    private var scrubAccumulatedDistanceX: Float = 0f
    private val scrubCommitRunnable = Runnable {
        endScrubbing()
    }

    fun dispatchTap(x: Float, y: Float) {
        if (currentScrollMode == ScrollMode.HORIZONTAL_SCRUB || isScrubbing) {
            endScrubbing()
            return
        }
        currentScrollMode = ScrollMode.IDLE
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        hideHandler.removeCallbacks(verticalScrollResetRunnable)

        val decor = window?.decorView ?: return
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        decor.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        hideHandler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            decor.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }, 60)
    }

    fun dispatchScroll(distanceX: Float, distanceY: Float) {
        val absX = Math.abs(distanceX)
        val absY = Math.abs(distanceY)

        when (currentScrollMode) {
            ScrollMode.VERTICAL -> {
                // 이미 위아래 스크롤이 진행 중인 상태 -> 좌우 드래그 인식을 절대 하지 않고 웹뷰 스크롤만 수행
                hideHandler.removeCallbacks(verticalScrollResetRunnable)
                hideHandler.postDelayed(verticalScrollResetRunnable, 300L)
                webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
            }

            ScrollMode.HORIZONTAL_SCRUB -> {
                // 이미 좌우 스크러빙 진행 중인 상태 -> 1000ms 버퍼로 일시 멈춤에도 안정 유지
                hideHandler.removeCallbacks(scrubCommitRunnable)
                hideHandler.postDelayed(scrubCommitRunnable, 1000L)

                scrubAccumulatedDistanceX += distanceX
                if (::scrubOverlay.isInitialized && scrubOverlay.visibility == View.VISIBLE) {
                    updateScrubUi(scrubAccumulatedDistanceX)
                }
            }

            ScrollMode.IDLE -> {
                // 제스처 방향 판정 단계 (누적 델타로 초기 방향 감지)
                pendingDeltaX += absX
                pendingDeltaY += absY

                val totalMove = pendingDeltaX + pendingDeltaY
                if (totalMove >= 8f) {
                    val isVertical = (pendingDeltaY >= pendingDeltaX * 1.0f) || (absY >= absX * 1.1f)
                    val isHorizontal = (pendingDeltaX >= pendingDeltaY * 1.5f) && (absX >= 4.0f)

                    if (isVertical) {
                        // 위아래 스크롤 세션으로 확정 (이후 가로 방향으로 꺾여도 스크러빙으로 전환되지 않음)
                        currentScrollMode = ScrollMode.VERTICAL
                        hideHandler.removeCallbacks(verticalScrollResetRunnable)
                        hideHandler.postDelayed(verticalScrollResetRunnable, 300L)
                        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
                    } else if (isHorizontal && CarWebViewManager.hasActivePlayer()) {
                        // 실제 영상 플레이어가 존재하는 경우에만 좌우 스크러빙 시작!
                        currentScrollMode = ScrollMode.HORIZONTAL_SCRUB
                        isScrubbing = true
                        startScrubbing(distanceX)
                    } else {
                        // 플레이어가 없거나(홈/검색 등) 판정이 애매한 경우 일반 웹뷰 스크롤
                        currentScrollMode = ScrollMode.VERTICAL
                        hideHandler.removeCallbacks(verticalScrollResetRunnable)
                        hideHandler.postDelayed(verticalScrollResetRunnable, 300L)
                        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
                    }
                } else {
                    // 판정 임계치 도달 전에는 기본 스크롤
                    webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
                }
            }
        }
    }

    private fun startScrubbing(initialDistanceX: Float) {
        scrubAccumulatedDistanceX = initialDistanceX
        hideHandler.removeCallbacks(scrubCommitRunnable)
        hideHandler.postDelayed(scrubCommitRunnable, 1000L)

        val queryJs = """
            (function() {
                var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                var v = document.querySelector('video');
                var isWatchOrShorts = location.pathname.indexOf('/watch') === 0 || location.pathname.indexOf('/shorts') === 0;
                var cur = (p && typeof p.getCurrentTime === 'function') ? p.getCurrentTime() : (v ? v.currentTime : 0);
                var dur = (p && typeof p.getDuration === 'function') ? p.getDuration() : (v ? v.duration : 0);
                if (!isWatchOrShorts && (!dur || dur <= 0)) {
                    return 'NO_PLAYER';
                }
                return cur + ',' + dur;
            })()
        """.trimIndent()
        webView?.evaluateJavascript(queryJs) { res ->
            try {
                val clean = res?.replace("\"", "") ?: ""
                if (clean == "NO_PLAYER") {
                    endScrubbing()
                    return@evaluateJavascript
                }
                val parts = clean.split(",")
                scrubInitialTime = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                scrubDuration = parts.getOrNull(1)?.toDoubleOrNull() ?: 600.0
                if (scrubDuration <= 0) scrubDuration = 600.0
                scrubTargetTime = scrubInitialTime

                if (::scrubOverlay.isInitialized && currentScrollMode == ScrollMode.HORIZONTAL_SCRUB) {
                    scrubOverlay.bringToFront()
                    scrubOverlay.translationZ = 120f
                    scrubOverlay.alpha = 1f
                    scrubOverlay.visibility = View.VISIBLE
                    updateScrubUi(scrubAccumulatedDistanceX)
                }
            } catch (_: Exception) {
                endScrubbing()
            }
        }
    }

    private fun updateScrubUi(accumulatedDistanceX: Float) {
        val duration = if (scrubDuration > 0) scrubDuration else 600.0
        val totalRange = Math.max(40.0, Math.min(duration * 0.20, 240.0))
        val screenW = if (targetWidth > 0) targetWidth.toDouble() else 800.0
        val secondsPerPixel = totalRange / screenW

        val deltaSec = -accumulatedDistanceX * secondsPerPixel
        scrubTargetTime = Math.max(0.0, Math.min(duration, scrubInitialTime + deltaSec))

        val diff = scrubTargetTime - scrubInitialTime
        val absDiff = Math.abs(diff).toInt()

        val diffText = if (absDiff < 60) {
            "${absDiff}초"
        } else {
            val m = absDiff / 60
            val s = absDiff % 60
            if (s == 0) "${m}분" else "${m}분 ${s}초"
        }

        if (diff >= 0) {
            tvScrubAction.text = "▶▶ 빨리감기 (+$diffText)"
            tvScrubAction.setTextColor(Color.parseColor("#FF4444"))
        } else {
            tvScrubAction.text = "◀◀ 되감기 (-$diffText)"
            tvScrubAction.setTextColor(Color.parseColor("#33AAFF"))
        }

        tvScrubTargetTime.text = formatSec(scrubTargetTime.toInt())
        tvScrubDuration.text = "/ ${formatSec(duration.toInt())}"

        val pct = (scrubTargetTime / duration) * 100.0
        val barJs = "(function(){ var b = document.querySelectorAll('.ytp-play-progress, .progress-bar-played'); for(var i=0; i<b.length; i++){ b[i].style.width = '${pct}%'; } })()"
        webView?.evaluateJavascript(barJs, null)
    }

    private fun formatSec(totalSec: Int): String {
        val sec = Math.max(0, totalSec)
        val m = sec / 60
        val s = sec % 60
        val h = m / 60
        val remM = m % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, remM, s)
        } else {
            String.format("%02d:%02d", remM, s)
        }
    }

    fun dispatchFling(velocityX: Float, velocityY: Float) {
        if (currentScrollMode == ScrollMode.HORIZONTAL_SCRUB || isScrubbing) {
            endScrubbing()
        } else if (currentScrollMode == ScrollMode.VERTICAL) {
            currentScrollMode = ScrollMode.IDLE
            pendingDeltaX = 0f
            pendingDeltaY = 0f
            hideHandler.removeCallbacks(verticalScrollResetRunnable)
        }
    }

    private fun endScrubbing() {
        if (currentScrollMode != ScrollMode.HORIZONTAL_SCRUB && !isScrubbing) return
        isScrubbing = false
        currentScrollMode = ScrollMode.IDLE
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        hideHandler.removeCallbacks(scrubCommitRunnable)

        if (::scrubOverlay.isInitialized) {
            scrubOverlay.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { scrubOverlay.visibility = View.GONE }
                .start()
        }

        val finalSec = scrubTargetTime
        val seekJs = """
            (function() {
                var targetTime = $finalSec;
                var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                var v = document.querySelector('video');
                if (p && typeof p.seekTo === 'function') {
                    p.seekTo(targetTime, true);
                    if (typeof p.playVideo === 'function') p.playVideo();
                }
                if (v) {
                    v.currentTime = targetTime;
                    if (v.paused) { try { v.play(); } catch(e) {} }
                }
                if (window.__atPush) setTimeout(window.__atPush, 100);
            })();
        """.trimIndent()
        webView?.evaluateJavascript(seekJs, null)
    }

    fun ensureWebViewAttached() {
        if (!::webViewContainer.isInitialized) return
        exitFullscreenIfNeeded()
        val current = webView
        if (current == null || current.parent !== webViewContainer) {
            Log.d("AutoPresentation", "Re-attaching WebView to webViewContainer")
            attachWebView()
        } else {
            current.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            current.requestLayout()
            current.invalidate()
        }
        webViewContainer.requestLayout()
        webViewContainer.invalidate()
        window?.decorView?.invalidate()
        webView?.post {
            webView?.requestLayout()
            webView?.evaluateJavascript("try { window.dispatchEvent(new Event('resize')); } catch(e){}", null)
        }
    }

    private fun attachWebView() {
        // 싱글톤 매니저로부터 유지 중인 WebView 획득 (가상 디스플레이 Context 적용)
        val targetWebView = CarWebViewManager.getOrCreateWebView(context, display)
        this.webView = targetWebView

        targetWebView.webViewClient = YouTubeCarWebViewClient(progressBar, null)
        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request ?: return
                hideHandler.post {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) request.grant(request.resources) else request.deny()
                }
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                presentationContainer.addView(
                    customView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                // 전체화면 상태에서도 상단 미니 바, 원형 백버튼 및 오버레이가 가려지지 않도록 최상단으로 올림
                topMiniControlBar.bringToFront()
                topMiniControlBar.translationZ = 100f
                if (::btnFloatingBack.isInitialized) {
                    btnFloatingBack.bringToFront()
                    btnFloatingBack.translationZ = 100f
                }
                if (::voiceSearchOverlay.isInitialized) {
                    voiceSearchOverlay.bringToFront()
                    voiceSearchOverlay.translationZ = 110f
                }
                if (::scrubOverlay.isInitialized) {
                    scrubOverlay.bringToFront()
                    scrubOverlay.translationZ = 120f
                }
                showMiniControlBar(3000L)
            }

            override fun onHideCustomView() {
                (customView?.parent as? ViewGroup)?.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        (targetWebView.parent as? ViewGroup)?.removeView(targetWebView)
        webViewContainer.removeAllViews()
        webViewContainer.addView(
            targetWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        targetWebView.requestFocus()
        targetWebView.post {
            targetWebView.requestLayout()
            targetWebView.invalidate()
            targetWebView.evaluateJavascript("try { window.dispatchEvent(new Event('resize')); } catch(e){}", null)
        }
    }

    override fun dismiss() {
        hideHandler.removeCallbacksAndMessages(null)
        exitFullscreenIfNeeded()
        super.dismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 프레젠테이션이 닫혀도 웹뷰를 파괴하지 않고 컨테이너에서만 분리
        webView?.let {
            webViewContainer.removeView(it)
        }
        webView = null
    }

    fun exitFullscreenIfNeeded() {
        if (customView != null) {
            (customView?.parent as? ViewGroup)?.removeView(customView)
            customView = null
            try {
                customViewCallback?.onCustomViewHidden()
            } catch (_: Exception) {}
            customViewCallback = null
            topMiniControlBar.bringToFront()
            if (::btnFloatingBack.isInitialized) {
                btnFloatingBack.bringToFront()
            }
        }
        val resetJs = """
            try {
                if (document.fullscreenElement) document.exitFullscreen();
                else if (document.webkitFullscreenElement) document.webkitExitFullscreen();
                window.dispatchEvent(new Event('resize'));
            } catch(e) {}
        """.trimIndent()
        webView?.evaluateJavascript(resetJs, null)
    }

    fun loadUrl(url: String) {
        exitFullscreenIfNeeded()
        webView?.loadUrl(url)
    }

    fun playPauseVideo() {
        val js = "var v = document.querySelector('video'); if (v) { v.paused ? v.play() : v.pause(); }"
        webView?.evaluateJavascript(js, null)
    }

    fun nextVideo() {
        val js = "var nextBtn = document.querySelector('.ytp-next-button'); if (nextBtn) nextBtn.click();"
        webView?.evaluateJavascript(js, null)
    }

    fun prevVideo() {
        val js = "var prevBtn = document.querySelector('.ytp-prev-button'); if (prevBtn) prevBtn.click(); else history.back();"
        webView?.evaluateJavascript(js, null)
    }
}
