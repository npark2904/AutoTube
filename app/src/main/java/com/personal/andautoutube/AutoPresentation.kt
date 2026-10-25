package com.personal.andautoutube

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.widget.ProgressBar

class AutoPresentation(
    outerContext: Context,
    display: Display,
    private val targetWidth: Int = 0,
    private val targetHeight: Int = 0
) : Presentation(outerContext, display, R.style.Theme_AutoTube_Presentation) {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var topMiniControlBar: LinearLayout
    private lateinit var btnMiniBack: ImageButton
    private lateinit var btnMiniHome: ImageButton
    private lateinit var btnMiniRefresh: ImageButton

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        topMiniControlBar.animate()
            .alpha(0.65f)
            .setDuration(350)
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_auto)

        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        webViewContainer = findViewById(R.id.webViewContainer)
        progressBar = findViewById(R.id.progressBar)
        topMiniControlBar = findViewById(R.id.topMiniControlBar)
        btnMiniBack = findViewById(R.id.btnMiniBack)
        btnMiniHome = findViewById(R.id.btnMiniHome)
        btnMiniRefresh = findViewById(R.id.btnMiniRefresh)

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
            val lp = attributes
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
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
        btnMiniBack.setOnClickListener {
            if (customView != null) {
                // 전체화면 재생 중이면 전체화면 먼저 해제
                (window?.decorView as? ViewGroup)?.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            } else {
                CarWebViewManager.goBack()
            }
            showMiniControlBar(1500L)
        }

        btnMiniHome.setOnClickListener {
            CarWebViewManager.loadHome()
            showMiniControlBar(1500L)
        }

        btnMiniRefresh.setOnClickListener {
            CarWebViewManager.reload()
            showMiniControlBar(1500L)
        }

        // 최초 진입 시 상단에 3초간 선명하게 띄운 뒤 은은한 반투명(0.4)으로 전환
        topMiniControlBar.alpha = 1f
        topMiniControlBar.visibility = View.VISIBLE
        showMiniControlBar(3000L)
    }

    fun showMiniControlBar(durationMs: Long = 2500L) {
        hideHandler.removeCallbacks(hideRunnable)
        topMiniControlBar.animate().cancel()
        topMiniControlBar.alpha = 1f
        topMiniControlBar.visibility = View.VISIBLE
        hideHandler.postDelayed(hideRunnable, durationMs)
    }

    /** 웹뷰는 항상 100% 전체화면을 유지하고(별도 여백/영역 없음), 우측 하단 백버튼만 가시 영역 우측 하단에 맞춰 오버레이 표시 */
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

        // 우측 하단 플로팅 백버튼을 화면 위에 오버레이로 배치
        val density = context.resources.displayMetrics.density
        val marginPx = (16 * density).toInt()
        val decorW = window?.decorView?.width ?: 0
        val decorH = window?.decorView?.height ?: 0

        val barLp = topMiniControlBar.layoutParams as? FrameLayout.LayoutParams
        barLp?.let {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.leftMargin = 0
            it.topMargin = 0
            val extraRight = if (decorW > visibleArea.right && visibleArea.right > 0) (decorW - visibleArea.right) else 0
            val extraBottom = if (decorH > visibleArea.bottom && visibleArea.bottom > 0) (decorH - visibleArea.bottom) else 0
            it.rightMargin = extraRight + marginPx
            it.bottomMargin = extraBottom + marginPx
            topMiniControlBar.layoutParams = it
        }
    }

    fun dispatchTap(x: Float, y: Float) {
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
        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    fun ensureWebViewAttached() {
        if (!::webViewContainer.isInitialized) return
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
    }

    private fun attachWebView() {
        // 싱글톤 매니저로부터 유지 중인 WebView 획득 (가상 디스플레이 Context 적용)
        val targetWebView = CarWebViewManager.getOrCreateWebView(context, display)
        this.webView = targetWebView

        targetWebView.webViewClient = YouTubeCarWebViewClient(progressBar, null)
        targetWebView.webChromeClient = object : WebChromeClient() {
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
                (window?.decorView as? ViewGroup)?.addView(
                    customView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                // 전체화면 상태에서도 상단 미니 바가 가려지지 않도록 최상단으로 올림
                topMiniControlBar.bringToFront()
            }

            override fun onHideCustomView() {
                (window?.decorView as? ViewGroup)?.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        webViewContainer.removeAllViews()
        webViewContainer.addView(
            targetWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        targetWebView.requestFocus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 프레젠테이션이 닫혀도 웹뷰를 파괴하지 않고 컨테이너에서만 분리
        webView?.let {
            webViewContainer.removeView(it)
        }
        webView = null
    }

    fun loadUrl(url: String) {
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
