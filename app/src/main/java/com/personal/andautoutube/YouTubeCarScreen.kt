package com.personal.andautoutube

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: AutoPresentation? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDpi = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isFullScreenEstablished = false
    private var isExitingToCard = false
    private val creationTime = System.currentTimeMillis()

    init {
        // 호스트의 화면 서피스 콜백 등록
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        // 앱 기동 후 초기화 레이아웃 안정화(500ms) 이후 전체화면 상태 확정
        mainHandler.postDelayed({
            if (!isExitingToCard) {
                val config = carContext.resources.configuration
                if (config.screenWidthDp >= 600) {
                    isFullScreenEstablished = true
                    Log.d(TAG, "FullScreen confirmed via initial delayed check (widthDp=${config.screenWidthDp})")
                }
            }
        }, 500)

        // Screen 생명주기에 맞춰 미디어 카드 중복 방지 (화면 표시 중일 땐 미디어 카드 비활성화, 백그라운드 전환 시 활성화)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                isExitingToCard = false
                AutoMediaBrowserService.setForegroundDisplaying(true)
            }

            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                isExitingToCard = false
                AutoMediaBrowserService.setForegroundDisplaying(true)
            }

            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
            }

            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                AutoMediaBrowserService.setForegroundDisplaying(false)
                mainHandler.post { dismissPresentation() }
            }
        })
    }

    /**
     * 전체화면에서 안드로이드 오토 화면 분할(대시보드 / Coolwalk) 버튼을 눌렀을 때
     * 다른 뮤직 앱처럼 AutoTube가 메인 뷰(Primary)를 차지하지 않고 이전 앱(네이버 지도 등)에게 메인 뷰를 양보한 뒤,
     * AutoTube는 우측 미디어 카드로 쏙 들어가도록 처리하는 로직
     */
    fun checkSplitScreenTransition(width: Int, height: Int, source: String) {
        if (isExitingToCard) return

        val config = carContext.resources.configuration
        val configWidthDp = config.screenWidthDp
        val configHeightDp = config.screenHeightDp

        val surfaceRatio = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
        val configRatio = if (configWidthDp > 0 && configHeightDp > 0) configWidthDp.toFloat() / configHeightDp.toFloat() else 0f

        Log.d(TAG, "checkSplitScreenTransition [$source]: size=${width}x${height} (ratio=%.2f), config=${configWidthDp}x${configHeightDp} (configRatio=%.2f), established=$isFullScreenEstablished".format(surfaceRatio, configRatio))

        // 전체 화면 판정: 너비가 600dp 이상이거나 비율이 1.5 이상
        val isFullScreen = (configWidthDp >= 600 || configRatio >= 1.5f) &&
                (width >= 600 || surfaceRatio >= 1.5f || surfaceRatio == 0f)

        // 분할 화면(대시보드 메인 슬롯) 판정: 너비가 550dp 이하로 줄어들고 비율이 1.45 미만으로 축소됨
        val isSplitScreen = (configWidthDp in 1..550 && configRatio in 0.1f..1.45f) ||
                (width in 1..550 && surfaceRatio in 0.1f..1.45f)

        val elapsed = System.currentTimeMillis() - creationTime

        if (isFullScreen) {
            if (elapsed > 300) {
                if (!isFullScreenEstablished) {
                    Log.d(TAG, "FullScreen established (elapsed=${elapsed}ms)")
                    isFullScreenEstablished = true
                    AutoMediaBrowserService.setForegroundDisplaying(true)
                }
            }
        } else if (isSplitScreen && isFullScreenEstablished) {
            Log.d(TAG, "Split screen / dashboard button clicked from fullscreen ($source)! Exiting main view to media card.")
            isExitingToCard = true
            AutoMediaBrowserService.setForegroundDisplaying(false)
            mainHandler.post {
                try {
                    carContext.finishCarApp()
                } catch (e: Exception) {
                    Log.e(TAG, "Error finishing car app", e)
                }
            }
        }
    }

    fun onCarConfigurationChanged(newConfig: Configuration) {
        Log.d(TAG, "onCarConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
        checkSplitScreenTransition(newConfig.screenWidthDp, newConfig.screenHeightDp, "onCarConfigurationChanged")
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "onVisibleAreaChanged: $visibleArea")
        checkSplitScreenTransition(visibleArea.width(), visibleArea.height(), "onVisibleAreaChanged")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "onStableAreaChanged: $stableArea")
        checkSplitScreenTransition(stableArea.width(), stableArea.height(), "onStableAreaChanged")
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        if (surface == null) {
            Log.e(TAG, "Surface is null in onSurfaceAvailable")
            return
        }

        Log.d(TAG, "onSurfaceAvailable: ${surfaceContainer.width}x${surfaceContainer.height}, dpi=${surfaceContainer.dpi}")
        checkSplitScreenTransition(surfaceContainer.width, surfaceContainer.height, "onSurfaceAvailable")

        mainHandler.post {
            try {
                val width = if (surfaceContainer.width > 0) surfaceContainer.width else 1280
                val height = if (surfaceContainer.height > 0) surfaceContainer.height else 720
                val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160

                // 이미 같은 크기의 가상 디스플레이/프레젠테이션이 살아있으면 서피스만 교체 (웹뷰 재생 유지)
                val existing = virtualDisplay
                if (existing != null && presentation != null &&
                    width == lastWidth && height == lastHeight && dpi == lastDpi) {
                    existing.surface = surface
                    Log.d(TAG, "Surface re-attached to existing VirtualDisplay (playback preserved)")
                    return@post
                }

                // 크기가 달라졌거나 최초 생성: 기존 것을 정리하고 새로 생성
                dismissPresentation()

                val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

                // 안드로이드 오토 서피스에 가상 디스플레이 매핑
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
                    presentation = AutoPresentation(carContext, display, width, height).apply {
                        show()
                    }
                    Log.d(TAG, "AutoPresentation successfully displayed on VirtualDisplay!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating presentation on surface", e)
            }
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.d(TAG, "onSurfaceDestroyed: keeping presentation attached so background playback continues")
        AutoMediaBrowserService.setForegroundDisplaying(false)
        mainHandler.post {
            // 프레젠테이션을 닫지 않고 서피스만 분리 -> 웹뷰가 윈도우에 붙은 채 유지되어 재생이 끊기지 않음
            try {
                virtualDisplay?.surface = null
            } catch (e: Exception) {
                Log.e(TAG, "Error detaching surface", e)
            }
            CarWebViewManager.keepAlive()
        }
    }

    override fun onClick(x: Float, y: Float) {
        mainHandler.post {
            presentation?.let { pres ->
                pres.showMiniControlBar(2000L)
                pres.dispatchTap(x, y)
            } ?: run {
                CarWebViewManager.dispatchTap(x, y)
            }
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            presentation?.let { pres ->
                pres.dispatchScroll(distanceX, distanceY)
            } ?: run {
                CarWebViewManager.dispatchScroll(distanceX, distanceY)
            }
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {}

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}

    private fun dismissPresentation() {
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
    override fun onGetTemplate(): Template {
        val config = carContext.resources.configuration
        checkSplitScreenTransition(config.screenWidthDp, config.screenHeightDp, "onGetTemplate")

        // 헤드유닛 화면에 전체 Surface를 렌더링하는 NavigationTemplate 반환
        // NavigationTemplate의 필수 ActionStrip 요건을 충족하면서도,
        // 우측 상단 순정의 거대한 Action.BACK FAB 버튼으로 화면이 가려지는 것을 방지하기 위해 48x48 완전 투명 CarIcon Action 배치
        // (1x1 등 너무 작은 크기는 호스트 렌더러에서 width <= 0 예외를 유발하므로 표준 크기 48x48 보장)
        val transparentBitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val transparentIcon = androidx.core.graphics.drawable.IconCompat.createWithBitmap(transparentBitmap)
        val transparentCarIcon = androidx.car.app.model.CarIcon.Builder(transparentIcon).build()

        val invisibleAction = Action.Builder()
            .setIcon(transparentCarIcon)
            .setOnClickListener {
                // 상단 중앙 미니 컨트롤바 선명화 보조
                presentation?.showMiniControlBar(2500L)
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
    }
}
