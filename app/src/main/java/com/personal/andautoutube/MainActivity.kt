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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection

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

    private var pendingWebPermission: android.webkit.PermissionRequest? = null
    private var backPressedTime: Long = 0L
    private var backToast: Toast? = null
    private var isCarConnectedByCarConnection = false

    private val permissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false
        val req = pendingWebPermission
        pendingWebPermission = null
        if (micGranted) req?.grant(req.resources) else req?.deny()
    }

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

        // 앱 시작 시 안드로이드 오토 네비게이션 권한(위치) 및 음성 검색(마이크) 권한을 휴대폰 화면에서 미리 요청
        val neededPermissions = mutableListOf<String>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            neededPermissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (neededPermissions.isNotEmpty()) {
            permissionsLauncher.launch(neededPermissions.toTypedArray())
        }

        // 공식 Android Auto CarConnection API를 통한 실제 차량 연결 여부 실시간 감지
        try {
            CarConnection(applicationContext).type.observe(this) { connectionType ->
                val connected = (connectionType == CarConnection.CONNECTION_TYPE_PROJECTION ||
                        connectionType == CarConnection.CONNECTION_TYPE_NATIVE)
                isCarConnectedByCarConnection = connected
                updateConnectionStatus()
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "CarConnection observe failed", e)
        }

        CarWebViewManager.carConnectionListener = { _ ->
            runOnUiThread {
                updateConnectionStatus()
            }
        }

        initViews()
        setupPreviewWebView()
        setupListeners()
        setupBackNavigation()

        AutoMediaBrowserService.mobileMediaActionListener = {
            runOnUiThread {
                previewWebView.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.paused ? v.play() : v.pause(); }", null)
            }
        }
        AutoMediaBrowserService.mobileNextListener = {
            runOnUiThread {
                previewWebView.evaluateJavascript("var nextBtn = document.querySelector('.ytp-next-button'); if (nextBtn) nextBtn.click();", null)
            }
        }
        AutoMediaBrowserService.mobilePrevListener = {
            runOnUiThread {
                previewWebView.evaluateJavascript("var prevBtn = document.querySelector('.ytp-prev-button'); if (prevBtn) prevBtn.click(); else { var v = document.querySelector('video'); if (v) v.currentTime = 0; }", null)
            }
        }

        CarWebViewManager.phoneUrlSyncListener = { url ->
            runOnUiThread {
                if (CarWebViewManager.getCanonicalKey(previewWebView.url) != CarWebViewManager.getCanonicalKey(url)) {
                    CarWebViewManager.isApplyingCarSyncToPhone = true
                    previewWebView.loadUrl(url)
                    previewWebView.postDelayed({
                        CarWebViewManager.isApplyingCarSyncToPhone = false
                    }, 1200L)
                }
            }
        }

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
        CarWebViewManager.getCurrentUrl()?.let { carUrl ->
            if (CarWebViewManager.getCanonicalKey(previewWebView.url) != CarWebViewManager.getCanonicalKey(carUrl)) {
                CarWebViewManager.isApplyingCarSyncToPhone = true
                previewWebView.loadUrl(carUrl)
                previewWebView.postDelayed({
                    CarWebViewManager.isApplyingCarSyncToPhone = false
                }, 1200L)
            }
        }
        previewWebView.onResume()
        previewWebView.resumeTimers()
        CarWebViewManager.keepAlive()
        updateConnectionStatus()
    }

    override fun onPause() {
        super.onPause()
        displayManager.unregisterDisplayListener(displayListener)
        // 스마트폰 앱이 백그라운드로 내려가도 웹뷰 타이머 및 차량/폰 재생이 멈추지 않도록 유지
        previewWebView.onResume()
        previewWebView.resumeTimers()
        if (CarWebViewManager.isCarConnected) {
            CarWebViewManager.keepAlive()
            CarWebViewManager.ensurePlayingInBackground()
        }
    }

    override fun onStop() {
        super.onStop()
        previewWebView.onResume()
        previewWebView.resumeTimers()
        if (CarWebViewManager.isCarConnected) {
            CarWebViewManager.keepAlive()
            CarWebViewManager.ensurePlayingInBackground()
        }
    }

    override fun onDestroy() {
        AutoMediaBrowserService.mobileMediaActionListener = null
        AutoMediaBrowserService.mobileNextListener = null
        AutoMediaBrowserService.mobilePrevListener = null
        CarWebViewManager.carConnectionListener = null
        if (!CarWebViewManager.isCarConnected) {
            AutoMediaBrowserService.stopServiceIfIdle()
        }
        CarWebViewManager.phoneUrlSyncListener = null
        super.onDestroy()
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

        previewWebView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onMediaStateChanged(title: String, isPlaying: Boolean) {
                runOnUiThread {
                    AutoMediaBrowserService.updateCurrentMediaFromMobile(title, isPlaying)
                }
            }

            @android.webkit.JavascriptInterface
            fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean) {
                onMediaDetailsChanged(title, artist, isPlaying, "")
            }

            @android.webkit.JavascriptInterface
            fun onMediaDetailsChanged(title: String, artist: String, isPlaying: Boolean, thumbnailUrl: String) {
                runOnUiThread {
                    AutoMediaBrowserService.updateCurrentMediaFromMobile(title, isPlaying, artist, thumbnailUrl)
                }
            }
        }, "AndroidMedia")

        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                previewWebView,
                CarWebViewManager.VISIBILITY_SPOOF_JS,
                setOf("*")
            )
        }

        previewWebView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrEmpty() && !CarWebViewManager.isApplyingCarSyncToPhone) {
                    CarWebViewManager.syncUrlFromPhone(url)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                phoneProgressBar.visibility = View.VISIBLE
                view?.evaluateJavascript(CarWebViewManager.VISIBILITY_SPOOF_JS, null)
                if (!url.isNullOrEmpty() && !CarWebViewManager.isApplyingCarSyncToPhone) {
                    // 모바일에서 영상을 선택하거나 이동하면 안드로이드 오토 차량 화면에도 실시간 즉시 동기화
                    CarWebViewManager.syncUrlFromPhone(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                phoneProgressBar.visibility = View.GONE
                view?.evaluateJavascript(CarWebViewManager.VISIBILITY_SPOOF_JS, null)
                val mediaWatchJs = """
                    (function() {
                        var __push = function() {
                            try {
                                var isWatchOrShorts = location.pathname.indexOf('/watch') === 0 || location.pathname.indexOf('/shorts') === 0;
                                var mainDom = document.querySelector('ytm-browse, ytm-rich-grid-renderer, [tab-identifier="FEwhat_to_watch"], ytm-pivot-bar-renderer, ytm-search');
                                var isMainScreen = !isWatchOrShorts && (location.pathname === '/' || !!mainDom);
                                var title = '';
                                var artist = 'AutoTube';

                                if (isMainScreen) {
                                    title = 'YouTube';
                                    artist = 'AutoTube';
                                } else {
                                    var titleElem = document.querySelector('h2.ytVideoMetadataViewModelTitle') || document.querySelector('ytd-watch-metadata #title h1 yt-formatted-string') || document.querySelector('ytd-watch-metadata #title h1') || document.querySelector('#title h1') || document.querySelector('.slim-video-information-title, ytm-slim-video-metadata-renderer .title, .video-details-title');
                                    title = (titleElem ? (titleElem.getAttribute('title') || titleElem.innerText || titleElem.textContent || '').trim() : '');
                                    if (!title) {
                                        var mt = document.querySelector('meta[property="og:title"]');
                                        var dt = (document.title || '').replace(/ - YouTube${'$'}/, '').trim();
                                        title = (dt && dt !== 'YouTube') ? dt : ((mt && mt.content) ? mt.content.trim() : '');
                                    }
                                    if (!title) title = 'YouTube';
                                    var channelElem = document.querySelector('.ytVideoSubtitleViewModelSubtitleContent .ytVideoSubtitleViewModelBoldSubstring') || document.querySelector('.slim-owner-channel-name, ytm-channel-name, .channel-name, .ytm-slim-owner-channel-name');
                                    artist = (channelElem ? channelElem.innerText.trim() : '') || 'AutoTube';
                                }

                                var thumbUrl = '';
                                var ogImg = document.querySelector('meta[property="og:image"]');
                                if (ogImg && ogImg.content) thumbUrl = ogImg.content.trim();
                                if (!thumbUrl) {
                                    var match = location.search.match(/[?&]v=([^&]+)/);
                                    if (match) thumbUrl = 'https://i.ytimg.com/vi/' + match[1] + '/hqdefault.jpg';
                                }

                                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                                var isPlaying = false;
                                if (player && typeof player.getPlayerState === 'function') {
                                    var pState = player.getPlayerState();
                                    isPlaying = (pState === 1 || pState === 3);
                                } else {
                                    var v = document.querySelector('video');
                                    if (v) isPlaying = (!v.paused && !v.ended && v.currentTime > 0);
                                }

                                if (window.AndroidMedia) {
                                    if (window.AndroidMedia.onMediaDetailsChanged) {
                                        window.AndroidMedia.onMediaDetailsChanged(title, artist, isPlaying, thumbUrl);
                                    } else {
                                        window.AndroidMedia.onMediaStateChanged(title, isPlaying);
                                    }
                                }
                            } catch(err) {}
                        };
                        if (window.__atMobileMediaHooked) { clearInterval(window.__atMobileMediaTimer); }
                        else {
                            window.__atMobileMediaHooked = true;
                            ['play','playing','pause','ended','seeked'].forEach(function(t){ document.addEventListener(t, function(){ setTimeout(__push, 50); }, true); });
                            ['click','touchend'].forEach(function(t){ document.addEventListener(t, function(){ [100,500,1200].forEach(function(ms){ setTimeout(__push, ms); }); }, true); });
                        }
                        window.__atMobileMediaTimer = setInterval(__push, 1000);
                        __push();
                    })();
                """.trimIndent()
                view?.evaluateJavascript(mediaWatchJs, null)
            }
        }

        previewWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request ?: return
                runOnUiThread {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            this@MainActivity, android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        permissionsLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                    }
                }
            }

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
            CarWebViewManager.syncUrlFromPhone(url)
        }

        btnPhoneBack.setOnClickListener {
            if (previewWebView.canGoBack()) {
                previewWebView.goBack()
            } else {
                val homeUrl = "https://m.youtube.com"
                if (CarWebViewManager.getCanonicalKey(previewWebView.url) != "home") {
                    previewWebView.loadUrl(homeUrl)
                    CarWebViewManager.syncUrlFromPhone(homeUrl)
                }
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

    private fun isHomeUrl(url: String?): Boolean {
        return CarWebViewManager.getCanonicalKey(url) == "home"
    }

    private fun setupBackNavigation() {
        // 스마트폰 뒤로가기 버튼(제스처) 처리:
        // 1. 이미 홈 화면인 경우 -> 2초 내에 한 번 더 누르면 앱 종료 UX 안내
        // 2. 홈 화면이 아닌 경우 -> 이전 히스토리가 있으면 뒤로가기, 없으면 홈(https://m.youtube.com)으로 이동
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isHomeUrl(previewWebView.url)) {
                    val now = System.currentTimeMillis()
                    if (now - backPressedTime < 2000L) {
                        backToast?.cancel()
                        finish()
                    } else {
                        backPressedTime = now
                        backToast?.cancel()
                        backToast = Toast.makeText(
                            this@MainActivity,
                            "한번더 백키를 누르면 종료합니다",
                            Toast.LENGTH_SHORT
                        )
                        backToast?.show()
                    }
                    return
                }

                if (previewWebView.canGoBack()) {
                    previewWebView.goBack()
                } else {
                    val homeUrl = "https://m.youtube.com"
                    if (CarWebViewManager.getCanonicalKey(previewWebView.url) != "home") {
                        previewWebView.loadUrl(homeUrl)
                        CarWebViewManager.syncUrlFromPhone(homeUrl)
                    }
                }
            }
        })
    }

    private fun updateConnectionStatus() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val isPresentationConnected = displays.any { it.displayId != Display.DEFAULT_DISPLAY }
        val isConnected = isPresentationConnected || CarWebViewManager.isCarConnected || isCarConnectedByCarConnection

        if (isConnected) {
            viewStatusIndicator.setBackgroundColor(getColor(android.R.color.holo_green_light))
            tvStatusTitle.text = getString(R.string.title_connected)
        } else {
            viewStatusIndicator.setBackgroundColor(getColor(R.color.youtube_red))
            tvStatusTitle.text = getString(R.string.title_disconnected)
        }
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
