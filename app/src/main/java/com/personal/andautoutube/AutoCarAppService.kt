package com.personal.andautoutube

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class AutoCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // 모든 호스트(Android Auto, DHU 등)의 접속을 허용
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return YouTubeCarSession()
    }

    override fun onDestroy() {
        CarWebViewManager.onCarDisconnected()
        super.onDestroy()
    }
}
