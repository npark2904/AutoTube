package com.personal.andautoutube

import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session

class YouTubeCarSession : Session() {

    private var currentScreen: YouTubeCarScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        // 폰 앱(MainActivity)을 거치지 않고 안드로이드 오토에서 바로 실행돼도
        // 미디어 세션이 활성화되어 하단 툴바/미디어 카드에 표시되도록 미디어 서비스를 포그라운드로 시작
        androidx.core.content.ContextCompat.startForegroundService(
            carContext,
            Intent(carContext, AutoMediaBrowserService::class.java)
        )
        return YouTubeCarScreen(carContext).also {
            currentScreen = it
        }
    }

    override fun onCarConfigurationChanged(newConfig: Configuration) {
        super.onCarConfigurationChanged(newConfig)
        Log.d(TAG, "onCarConfigurationChanged: widthDp=${newConfig.screenWidthDp}, heightDp=${newConfig.screenHeightDp}")
        currentScreen?.onCarConfigurationChanged(newConfig)
    }

    companion object {
        private const val TAG = "YouTubeCarSession"
    }
}
