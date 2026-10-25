package com.personal.andautoutube

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

class YouTubeCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var maxObservedWidthPx = 0
    private var maxObservedConfigWidthDp = 0
    private var lastVisibleRect: Rect? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isFullScreenEstablished = false
    private var isExitingToCard = false
    private val creationTime = System.currentTimeMillis()

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false
    private var lastListenStartTime = 0L

    private fun updateActionStripState(listening: Boolean) {
        isListening = listening
        try {
            invalidate()
        } catch (_: Exception) {}
    }

    fun cancelVoiceSearch() {
        mainHandler.post {
            updateActionStripState(false)
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            CarWebViewManager.getPresentation()?.hideVoiceOverlay()
        }
    }

    private fun toast(msg: String) {
        // 전용 음성 오버레이 카드가 표시되므로 CarToast는 호출하지 않음
    }

    /** 음성 검색 시작: 화면 중앙 전용 오버레이 UI 및 상단 마이크 버튼 색상 변경으로 상태를 확실히 표시 */
    fun startVoiceSearch(isRetry: Boolean = false) {
        mainHandler.post {
            val now = System.currentTimeMillis()
            if (!isRetry && isListening && (now - lastListenStartTime) < 8000L) {
                cancelVoiceSearch()
                return@post
            }
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                carContext, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                CarWebViewManager.getPresentation()?.showVoiceOverlay(
                    "마이크 권한 필요",
                    "휴대폰 화면에서 마이크 권한을 허용해 주세요",
                    3000L
                )
                toast("휴대폰에서 마이크 권한을 허용해 주세요")
                carContext.requestPermissions(listOf(android.Manifest.permission.RECORD_AUDIO)) { allowed, _ ->
                    if (allowed.contains(android.Manifest.permission.RECORD_AUDIO)) startVoiceSearch()
                }
                return@post
            }
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(carContext)) {
                CarWebViewManager.getPresentation()?.showVoiceOverlay(
                    "음성 인식 불가",
                    "기기의 음성 인식 서비스를 사용할 수 없습니다",
                    2500L
                )
                toast("음성 인식 서비스를 사용할 수 없습니다")
                return@post
            }

            // 마이크가 백그라운드에서 무음 처리되지 않도록 포그라운드 서비스 보장
            AutoPresentationService.start(carContext)

            // 즉시 시각적 오버레이 및 상단 빨간 마이크 아이콘 표시
            lastListenStartTime = now
            updateActionStripState(true)
            CarWebViewManager.getPresentation()?.showVoiceOverlay(
                "듣는 중...",
                "검색할 단어를 말씀해 주세요 (터치 시 취소)"
            )

            // 제미나이 등 시스템 음성인식 사용 후 기존 세션 충돌 방지를 위해 완전히 재생성
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null

            val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(carContext.applicationContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    lastListenStartTime = System.currentTimeMillis()
                    updateActionStripState(true)
                    CarWebViewManager.getPresentation()?.showVoiceOverlay(
                        "듣는 중...",
                        "검색할 단어를 말씀해 주세요 (터치 시 취소)"
                    )
                    toast("듣는 중... 말씀해 주세요")
                }
                override fun onBeginningOfSpeech() {
                    CarWebViewManager.getPresentation()?.showVoiceOverlay(
                        "음성 입력 감지됨...",
                        "계속 말씀해 주세요"
                    )
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    CarWebViewManager.getPresentation()?.showVoiceOverlay(
                        "음성 분석 중...",
                        "잠시만 기다려 주세요"
                    )
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!partial.isNullOrEmpty()) {
                        CarWebViewManager.getPresentation()?.showVoiceOverlay(
                            "“$partial”",
                            "인식 중..."
                        )
                    }
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error=$error (isRetry=$isRetry)")
                    // 제미나이 종료 직후 마이크/인식기가 아직 점유 중(BUSY/CLIENT)이면 400ms 후 1회 자동 재시도
                    if (!isRetry && (error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                error == android.speech.SpeechRecognizer.ERROR_CLIENT)) {
                        mainHandler.postDelayed({ startVoiceSearch(isRetry = true) }, 400L)
                        return
                    }
                    updateActionStripState(false)
                    val errMsg = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성을 인식하지 못했습니다"
                        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다"
                        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다. 잠시 후 다시 눌러주세요"
                        else -> "음성 인식 오류 (코드: $error)"
                    }
                    CarWebViewManager.getPresentation()?.showVoiceOverlay(
                        errMsg,
                        "우측 상단 마이크 버튼을 다시 눌러주세요",
                        2000L
                    )
                    toast(errMsg)
                }
                override fun onResults(results: android.os.Bundle?) {
                    updateActionStripState(false)
                    val text = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (text.isNullOrEmpty()) {
                        CarWebViewManager.getPresentation()?.showVoiceOverlay(
                            "음성을 인식하지 못했습니다",
                            "다시 시도해 주세요",
                            1800L
                        )
                        toast("음성을 인식하지 못했습니다")
                        return
                    }
                    CarWebViewManager.getPresentation()?.showVoiceOverlay(
                        "🔍 “$text”",
                        "유튜브에서 검색합니다...",
                        1200L
                    )
                    toast("검색: $text")
                    val url = "https://m.youtube.com/results?search_query=" + Uri.encode(text)
                    CarWebViewManager.loadUrl(url)
                    CarWebViewManager.getPresentation()?.showMiniControlBar(2000L)
                }
            })
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, carContext.packageName)
            }
            recognizer.startListening(intent)
        }
    }

    private fun voiceSearchAction(): Action {
        val tintColor = if (isListening) CarColor.RED else CarColor.DEFAULT
        val icon = androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, R.drawable.ic_mic)
        ).setTint(tintColor).build()
        return Action.Builder()
            .setIcon(icon)
            .setOnClickListener { startVoiceSearch() }
            .build()
    }

    init {
        CarWebViewManager.voiceSearchTrigger = { startVoiceSearch() }
        CarWebViewManager.voiceSearchCancelTrigger = { cancelVoiceSearch() }
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                // 제미나이 등 시스템 어시스턴트가 호출될 때 기존 음성인식 세션을 즉시 해제하여 충돌 방지
                if (isListening || speechRecognizer != null) {
                    cancelVoiceSearch()
                }
            }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                if (CarWebViewManager.voiceSearchTrigger != null) {
                    CarWebViewManager.voiceSearchTrigger = null
                }
                if (CarWebViewManager.voiceSearchCancelTrigger != null) {
                    CarWebViewManager.voiceSearchCancelTrigger = null
                }
                cancelVoiceSearch()
                AutoMediaBrowserService.setForegroundDisplaying(false)
                CarWebViewManager.detachSurface()
            }
        })
    }

    init {
        // Action.BACK(호스트 뒤로가기 버튼) -> 웹뷰 뒤로가기로 동작
        carContext.onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                mainHandler.post {
                    CarWebViewManager.getPresentation()?.showMiniControlBar(2000L)
                    CarWebViewManager.goBack()
                }
            }
        })
        // 새 화면이 만들어졌다는 것은 사용자가 다시 AutoTube를 연 것이므로 이전 내비 전환 상태를 초기화 (즉시 재종료 -> 검은 화면 방지)
        lastExitToCardTime = 0L
        // 호스트의 화면 서피스 콜백 등록
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        val initConfig = carContext.resources.configuration
        if (initConfig.screenWidthDp > maxObservedConfigWidthDp) {
            maxObservedConfigWidthDp = initConfig.screenWidthDp
        }

        // 앱 기동 후 레이아웃 안정화(350ms) 이후 분할화면 여부 재확인 및 전체화면 상태 확정
        mainHandler.postDelayed({
            if (!isExitingToCard) {
                val rect = lastVisibleRect
                if (rect != null) {
                    val fullH = maxOf(surfaceHeight, rect.bottom, rect.height())
                    checkSplitScreenTransition(rect.width(), fullH, "delayedInitCheck", rect)
                }
                if (!isExitingToCard) {
                    isFullScreenEstablished = true
                    Log.d(TAG, "FullScreen confirmed via delayed check (surface=${surfaceWidth}x${surfaceHeight}, visible=$lastVisibleRect)")
                }
            }
        }, 350)

        // Screen 생명주기에 맞춰 미디어 카드 상태 동기화 (Screen이 종료되어도 Presentation은 닫지 않아 백그라운드 재생 유지!)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                if (!isExitingToCard) {
                    AutoMediaBrowserService.setForegroundDisplaying(true)
                }
                CarWebViewManager.keepAlive()
            }

            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                if (!isExitingToCard) {
                    AutoMediaBrowserService.setForegroundDisplaying(true)
                }
                CarWebViewManager.keepAlive()
            }

            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
                CarWebViewManager.keepAlive()
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
                CarWebViewManager.keepAlive()
            }

            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
                // 호스트 서피스가 파괴되기 전에 동기적으로 즉시 분리
                CarWebViewManager.detachSurface()
                mainHandler.removeCallbacksAndMessages(null)
            }
            private fun onDestroyUnused() {
                AutoMediaBrowserService.setForegroundDisplaying(false)
                mainHandler.post {
                    // 주의: Presentation을 dismiss하면 웹뷰가 윈도우에서 분리되어 백그라운드 재생이 멈추므로 서피스만 분리!
                    CarWebViewManager.detachSurface()
                }
            }
        })
    }

    /**
     * 전체화면에서 안드로이드 오토 좌측 하단 화면 분할(대시보드 / Coolwalk) 버튼을 눌렀을 때
     * AutoTube가 메인 뷰(Primary)와 우측 카드뷰에 중복 표시되지 않고,
     * 메인 뷰에서 즉시 빠져나와 우측 분할 카드뷰에서만 표시되도록 처리하는 로직
     */
    fun checkSplitScreenTransition(
        width: Int,
        height: Int,
        source: String,
        visibleRect: Rect? = null
    ) {
        if (isExitingToCard) return

        val config = carContext.resources.configuration
        val configWidthDp = config.screenWidthDp
        val configHeightDp = config.screenHeightDp
        if (configWidthDp > maxObservedConfigWidthDp) {
            maxObservedConfigWidthDp = configWidthDp
        }

        val isConfigSource = (source == "onCarConfigurationChanged" || source == "onGetTemplate")
        if (!isConfigSource && width > maxObservedWidthPx) {
            maxObservedWidthPx = width
        }

        val refFullWidthPx = maxOf(surfaceWidth, maxObservedWidthPx)
        val effectiveRatio = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
        val configRatio = if (configWidthDp > 0 && configHeightDp > 0) configWidthDp.toFloat() / configHeightDp.toFloat() else 0f

        // 분할 화면(Coolwalk 대시보드 메인 슬롯) 판정:
        // 1) 가시 영역/서피스 가로 폭이 전체 가로 폭 대비 82% 미만으로 축소된 경우 (우측에 미디어 카드가 배치됨)
        val widthShrankFromFullPx = !isConfigSource && refFullWidthPx > 0 &&
                width in 1 until (refFullWidthPx * 0.82f).toInt()

        // 2) visibleRect의 좌/우 여백 합계가 전체 서피스 폭의 20%를 초과하는 경우 (분할 카드로 인한 영역 축소)
        val hasSplitCardHorizontalInset = visibleRect != null && surfaceWidth > 0 &&
                (surfaceWidth - visibleRect.width()) > (surfaceWidth * 0.20f).toInt()

        // 3) Configuration screenWidthDp가 최대 관측치 대비 82% 미만으로 축소되었거나 분할 비율인 경우
        val configIndicatesSplit = (maxObservedConfigWidthDp > 0 &&
                configWidthDp in 1 until (maxObservedConfigWidthDp * 0.82f).toInt()) ||
                (configWidthDp in 1..560 && configRatio in 0.1f..1.45f)

        // 4) 실제 전체 높이 기준 종횡비가 1.45 이하인 경우 (차량 전체화면은 항상 1.55 이상)
        val ratioIndicatesSplit = !isConfigSource && effectiveRatio in 0.1f..1.45f

        val isSplitScreen = widthShrankFromFullPx ||
                hasSplitCardHorizontalInset ||
                configIndicatesSplit ||
                ratioIndicatesSplit

        val elapsed = System.currentTimeMillis() - creationTime
        val recentlyExited = (System.currentTimeMillis() - lastExitToCardTime) < 3500L

        Log.d(
            TAG,
            "checkSplitScreenTransition [$source]: size=${width}x${height} (ratio=%.2f), refFullW=$refFullWidthPx, config=${configWidthDp}x${configHeightDp} (cRatio=%.2f), isSplit=$isSplitScreen, established=$isFullScreenEstablished".format(
                effectiveRatio,
                configRatio
            )
        )

        if (isSplitScreen) {
            if (isFullScreenEstablished || recentlyExited || elapsed > 300L) {
                exitToMediaCard(source)
            }
        } else if (width > 0 || configWidthDp >= 600) {
            if (!isFullScreenEstablished) {
                isFullScreenEstablished = true
                AutoMediaBrowserService.setForegroundDisplaying(true)
                Log.d(TAG, "FullScreen established via $source (elapsed=${elapsed}ms)")
            }
        }
    }

    /** 설치된 내비 앱을 패키지를 명시해 실행 (패키지 미지정 시 AutoTube로 재라우팅되어 루프 발생) */
    private fun launchNavigationApp(): Boolean {
        val pm = carContext.packageManager
        val query = Intent("androidx.car.app.CarAppService").addCategory("androidx.car.app.category.NAVIGATION")
        val services = pm.queryIntentServices(query, 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != carContext.packageName }
            .sortedBy { val i = NAV_PACKAGES.indexOf(it.packageName); if (i < 0) Int.MAX_VALUE else i }
        for (si in services) {
            try {
                // 내비 인텐트는 component 지정이 금지(SecurityException)되므로 패키지만 지정
                val intent = Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0"))
                    .setPackage(si.packageName)
                carContext.startCarApp(intent)
                Log.d(TAG, "Launched navigation car app: ${si.packageName}/${si.name}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch ${si.packageName}", e)
            }
        }
        return false
    }

    private fun exitToMediaCard(source: String) {
        // 분할 화면에서도 메인 뷰의 AutoTube를 유지 (내비 앱 전환/종료 없음)
        if (!EXIT_ON_SPLIT_SCREEN) return
        if (isExitingToCard) return
        isExitingToCard = true
        lastExitToCardTime = System.currentTimeMillis()
        Log.d(TAG, "Split-screen mode detected ($source)! Exiting main view so AutoTube shows ONLY in media card.")

        // 우측 미디어 카드 활성화 및 백그라운드 웹뷰 재생 유지
        AutoMediaBrowserService.setForegroundDisplaying(false)
        CarWebViewManager.keepAlive()

        mainHandler.post {
            // 프레젠테이션을 닫지 않고 오프스크린 더미 서피스로 전환하여 백그라운드 재생을 유지하면서 메인뷰에는 표시되지 않게 처리
            CarWebViewManager.detachSurface()

            // 주의: 여기서 CarContext.ACTION_NAVIGATE(패키지 미지정)를 호출하면 Gearhead가 현재 CarApp(AutoTube)으로
            // 인텐트를 다시 라우팅하여 메인 뷰 <-> 카드 뷰 무한 루프가 발생하므로 finishCarApp()만 단독 호출함!
            try {
                if (!launchNavigationApp()) carContext.finishCarApp()
                Log.d(TAG, "carContext.finishCarApp() called (exiting to media card without nav loop)")
            } catch (e: Exception) {
                Log.e(TAG, "Error calling finishCarApp()", e)
            }

            // 화면 전환 직후 웹뷰 재생 상태 재확인 및 깨우기
            // 세션 종료(DHU 닫기) 시 닫히는 호스트로 오디오/비디오 유입 방지를 위해 재생 강제 트리거를 호출하지 않음
        }
    }

    fun onCarConfigurationChanged(newConfig: Configuration) {
        Log.d(TAG, "onCarConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
        checkSplitScreenTransition(newConfig.screenWidthDp, newConfig.screenHeightDp, "onCarConfigurationChanged")
    }

    /** 같은 Screen 인스턴스가 재사용되어 다시 전체화면이 된 경우(내비 전환 후 복귀) 종료 상태를 해제하고 서피스를 재연결 */
    private fun tryResumeFromExit(visibleArea: Rect) {
        if (!isExitingToCard) return
        val sc = lastSurfaceContainer ?: return
        val full = surfaceWidth > 0 && visibleArea.width() >= surfaceWidth * 0.9f
        if (!full || System.currentTimeMillis() - lastExitToCardTime < 1500L) return
        val surface = sc.surface ?: return
        Log.d(TAG, "Full screen restored after exit -> resuming main view")
        isExitingToCard = false
        isFullScreenEstablished = true
        lastExitToCardTime = 0L
        AutoMediaBrowserService.setForegroundDisplaying(true)
        mainHandler.post {
            val w = if (sc.width > 0) sc.width else 1280
            val h = if (sc.height > 0) sc.height else 720
            val d = if (sc.dpi > 0) sc.dpi else 160
            CarWebViewManager.attachSurface(carContext, surface, w, h, d, lastVisibleRect)
        }
    }

    private var lastSurfaceContainer: SurfaceContainer? = null

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        lastVisibleRect = Rect(visibleArea)
        tryResumeFromExit(visibleArea)
        val w = visibleArea.width()
        val fullH = maxOf(surfaceHeight, visibleArea.bottom, visibleArea.height())
        Log.d(TAG, "onVisibleAreaChanged: $visibleArea (w=$w, rawH=${visibleArea.height()}, fullH=$fullH)")
        checkSplitScreenTransition(w, fullH, "onVisibleAreaChanged", visibleArea)

        if (!isExitingToCard) {
            mainHandler.post {
                CarWebViewManager.getPresentation()?.updateVisibleArea(visibleArea)
            }
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        val w = stableArea.width()
        val fullH = maxOf(surfaceHeight, stableArea.bottom, stableArea.height())
        Log.d(TAG, "onStableAreaChanged: $stableArea (w=$w, fullH=$fullH)")
        checkSplitScreenTransition(w, fullH, "onStableAreaChanged", stableArea)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        if (surface == null) {
            Log.e(TAG, "Surface is null in onSurfaceAvailable")
            return
        }

        lastSurfaceContainer = surfaceContainer
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        if (surfaceWidth > maxObservedWidthPx) {
            maxObservedWidthPx = surfaceWidth
        }

        Log.d(TAG, "onSurfaceAvailable: ${surfaceContainer.width}x${surfaceContainer.height}, dpi=${surfaceContainer.dpi}")
        checkSplitScreenTransition(surfaceContainer.width, surfaceContainer.height, "onSurfaceAvailable")

        if (isExitingToCard) {
            Log.d(TAG, "Ignoring onSurfaceAvailable because app is exiting to media card")
            return
        }

        mainHandler.post {
            val width = if (surfaceContainer.width > 0) surfaceContainer.width else 1280
            val height = if (surfaceContainer.height > 0) surfaceContainer.height else 720
            val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160
            CarWebViewManager.attachSurface(carContext, surface, width, height, dpi, lastVisibleRect)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        lastSurfaceContainer = null
        Log.d(TAG, "onSurfaceDestroyed: detaching surface synchronously")
        AutoMediaBrowserService.setForegroundDisplaying(false)
        // 호스트가 서피스를 회수하기 전에 동기적으로 즉시 분리해야 DHU/차량 인코더 크래시를 방지함
        CarWebViewManager.detachSurface()
        mainHandler.post {
            // 세션 종료(DHU 닫기) 시 닫히는 호스트로 오디오/비디오 유입 방지를 위해 재생 강제 트리거를 호출하지 않음
        }
    }
    private fun onSurfaceDestroyedUnused(surfaceContainer: SurfaceContainer) {
        lastSurfaceContainer = null
        Log.d(TAG, "onSurfaceDestroyed: keeping presentation attached so background playback continues")
        AutoMediaBrowserService.setForegroundDisplaying(false)
        mainHandler.post {
            // 프레젠테이션을 닫지 않고 서피스만 분리 -> 웹뷰가 윈도우에 붙은 채 유지되어 재생이 끊기지 않음
            CarWebViewManager.detachSurface()
            // 세션 종료(DHU 닫기) 시 닫히는 호스트로 오디오/비디오 유입 방지를 위해 재생 강제 트리거를 호출하지 않음
        }
    }

    override fun onClick(x: Float, y: Float) {
        mainHandler.post {
            CarWebViewManager.getPresentation()?.let { pres ->
                pres.showMiniControlBar(2000L)
                pres.dispatchTap(x, y)
            } ?: run {
                CarWebViewManager.dispatchTap(x, y)
            }
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            CarWebViewManager.getPresentation()?.let { pres ->
                pres.dispatchScroll(distanceX, distanceY)
            } ?: run {
                CarWebViewManager.dispatchScroll(distanceX, distanceY)
            }
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            CarWebViewManager.getPresentation()?.let { pres ->
                pres.dispatchFling(velocityX, velocityY)
            } ?: run {
                CarWebViewManager.dispatchFling(velocityX, velocityY)
            }
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}

    override fun onGetTemplate(): Template {
        val config = carContext.resources.configuration
        checkSplitScreenTransition(config.screenWidthDp, config.screenHeightDp, "onGetTemplate")

        // 헤드유닛 화면에 전체 Surface를 렌더링하는 NavigationTemplate 반환
        val transparentBitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val transparentIcon = androidx.core.graphics.drawable.IconCompat.createWithBitmap(transparentBitmap)
        val transparentCarIcon = androidx.car.app.model.CarIcon.Builder(transparentIcon).build()

        val invisibleAction = Action.Builder()
            .setIcon(transparentCarIcon)
            .setOnClickListener {
                CarWebViewManager.getPresentation()?.showMiniControlBar(2500L)
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(voiceSearchAction())
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setBackgroundColor(CarColor.PRIMARY)
            .build()
    }

    companion object {
        private const val TAG = "YouTubeCarScreen"
        private const val EXIT_ON_SPLIT_SCREEN = false
        private val NAV_PACKAGES = listOf("com.nhn.android.nmap", "com.skt.tmap.ku", "com.locnall.KimGiSa", "com.waze")
        private var lastExitToCardTime = 0L
    }
}
