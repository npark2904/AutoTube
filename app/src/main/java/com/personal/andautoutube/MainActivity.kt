package com.personal.andautoutube

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var displayManager: DisplayManager
    private lateinit var viewStatusIndicator: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var previewWebView: WebView
    private lateinit var btnQuickHome: Button
    private lateinit var btnStartTestGuide: ImageButton
    private lateinit var btnPhoneBack: ImageButton
    private lateinit var btnPhoneForward: ImageButton
    private lateinit var btnPhoneRefresh: ImageButton
    private lateinit var phoneProgressBar: ProgressBar

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updateConnectionStatus()
        }

        override fun onDisplayRemoved(displayId: Int) {
            updateConnectionStatus()
        }

        override fun onDisplayChanged(displayId: Int) {
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        initViews()
        setupPreviewWebView()
        setupListeners()
        setupBackNavigation()

        // 프레젠테이션 및 안드로이드 오토 미디어 서비스 시작
        AutoPresentationService.start(this)
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, AutoMediaBrowserService::class.java)
        )
        updateConnectionStatus()
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, null)
        updateConnectionStatus()
    }

    override fun onPause() {
        super.onPause()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun initViews() {
        viewStatusIndicator = findViewById(R.id.viewStatusIndicator)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        previewWebView = findViewById(R.id.previewWebView)
        btnQuickHome = findViewById(R.id.btnQuickHome)
        btnStartTestGuide = findViewById(R.id.btnStartTestGuide)
        btnPhoneBack = findViewById(R.id.btnPhoneBack)
        btnPhoneForward = findViewById(R.id.btnPhoneForward)
        btnPhoneRefresh = findViewById(R.id.btnPhoneRefresh)
        phoneProgressBar = findViewById(R.id.phoneProgressBar)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreviewWebView() {
        val settings = previewWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // 스크롤 및 터치 조작 최적화
        previewWebView.isVerticalScrollBarEnabled = true
        previewWebView.isHorizontalScrollBarEnabled = false
        previewWebView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        // 쿠키 동기화
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(previewWebView, true)

        previewWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                phoneProgressBar.visibility = View.VISIBLE
                if (!url.isNullOrEmpty()) {
                    // 모바일에서 영상을 선택하거나 이동하면 안드로이드 오토 차량 화면에도 실시간 즉시 동기화!
                    CarWebViewManager.loadUrl(url)
                    sendUrlToAutoPresentation(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                phoneProgressBar.visibility = View.GONE
            }
        }

        previewWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    phoneProgressBar.visibility = View.GONE
                } else {
                    phoneProgressBar.visibility = View.VISIBLE
                }
            }
        }

        previewWebView.loadUrl("https://m.youtube.com")
    }

    private fun setupListeners() {
        btnQuickHome.setOnClickListener {
            val url = "https://m.youtube.com"
            previewWebView.loadUrl(url)
            sendUrlToAutoPresentation(url)
        }

        btnPhoneBack.setOnClickListener {
            if (previewWebView.canGoBack()) {
                previewWebView.goBack()
            }
        }

        btnPhoneForward.setOnClickListener {
            if (previewWebView.canGoForward()) {
                previewWebView.goForward()
            }
        }

        btnPhoneRefresh.setOnClickListener {
            previewWebView.reload()
        }

        btnStartTestGuide.setOnClickListener {
            showTestGuideDialog()
        }
    }

    private fun setupBackNavigation() {
        // 스마트폰 뒤로가기 버튼(제스처)을 누를 때 웹뷰가 이전 페이지로 가도록 처리
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewWebView.canGoBack()) {
                    previewWebView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun updateConnectionStatus() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val isConnected = displays.any { it.displayId != Display.DEFAULT_DISPLAY }

        if (isConnected) {
            viewStatusIndicator.setBackgroundColor(getColor(android.R.color.holo_green_light))
            tvStatusTitle.text = getString(R.string.title_connected)
        } else {
            viewStatusIndicator.setBackgroundColor(getColor(R.color.youtube_red))
            tvStatusTitle.text = getString(R.string.title_disconnected)
        }
    }

    private fun sendUrlToAutoPresentation(url: String) {
        val intent = Intent(this, AutoPresentationService::class.java).apply {
            action = AutoPresentationService.ACTION_LOAD_URL
            putExtra(AutoPresentationService.EXTRA_URL, url)
        }
        startService(intent)
    }

    private fun showTestGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("안드로이드 오토 테스트 가이드")
            .setMessage(
                """
                1. 스마트폰에서 '안드로이드 오토' 앱 설정 진입
                2. 설정 맨 아래 '버전' 영역을 10번 연속 터치하여 개발자 모드 활성화
                3. 우측 상단 메뉴(점 3개) > '개발자 설정' > '알 수 없는 소스' 허용
                4. 개발자 모드 메뉴에서 '헤드유닛 서버 시작' 터치
                5. 스마트폰을 PC에 USB로 연결
                6. PC에서 'start-dhu.bat' 실행
                
                PC 모니터에 차량 안드로이드 오토 화면이 표시되며 AutoTube를 테스트할 수 있습니다!
                """.trimIndent()
            )
            .setPositiveButton("확인", null)
            .show()
    }
}
