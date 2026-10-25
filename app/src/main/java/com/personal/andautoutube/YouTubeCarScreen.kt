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

    init {
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

    private fun exitToMediaCard(source: String) {
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
                carContext.finishCarApp()
                Log.d(TAG, "carContext.finishCarApp() called (exiting to media card without nav loop)")
            } catch (e: Exception) {
                Log.e(TAG, "Error calling finishCarApp()", e)
            }

            // 화면 전환 직후 웹뷰 재생 상태 재확인 및 깨우기
            CarWebViewManager.ensurePlayingInBackground()
        }
    }

    fun onCarConfigurationChanged(newConfig: Configuration) {
        Log.d(TAG, "onCarConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
        checkSplitScreenTransition(newConfig.screenWidthDp, newConfig.screenHeightDp, "onCarConfigurationChanged")
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        lastVisibleRect = Rect(visibleArea)
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
        Log.d(TAG, "onSurfaceDestroyed: keeping presentation attached so background playback continues")
        AutoMediaBrowserService.setForegroundDisplaying(false)
        mainHandler.post {
            // 프레젠테이션을 닫지 않고 서피스만 분리 -> 웹뷰가 윈도우에 붙은 채 유지되어 재생이 끊기지 않음
            CarWebViewManager.detachSurface()
            CarWebViewManager.ensurePlayingInBackground()
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

    override fun onFling(velocityX: Float, velocityY: Float) {}

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
            .addAction(invisibleAction)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setBackgroundColor(CarColor.PRIMARY)
            .build()
    }

    companion object {
        private const val TAG = "YouTubeCarScreen"
        private var lastExitToCardTime = 0L
    }
}
