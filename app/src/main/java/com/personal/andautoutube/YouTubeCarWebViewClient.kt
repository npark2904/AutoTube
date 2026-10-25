package com.personal.andautoutube

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView

class YouTubeCarWebViewClient(
    private val progressBar: ProgressBar?,
    private val tvUrlDisplay: TextView?,
    private val onPageFinishedListener: ((url: String) -> Unit)? = null
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // 모든 링크를 내부 웹뷰에서 탐색하도록 처리
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 10
        url?.let {
            tvUrlDisplay?.text = it
        }
        view?.evaluateJavascript(CarWebViewManager.VISIBILITY_SPOOF_JS, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        progressBar?.visibility = View.GONE
        url?.let {
            tvUrlDisplay?.text = it
            onPageFinishedListener?.invoke(it)
        }
        view?.evaluateJavascript(CarWebViewManager.VISIBILITY_SPOOF_JS, null)

        // 차량 화면에 맞춤 스크립트 주입 (가로형 와이드 디스플레이 및 플레이 타임 바 터치 최적화)
        val carOptimizingJs = """
            (function() {
                // 1. 모바일 유튜브에서 상단 '앱 열기' 유도 배너 제거
                var appPromo = document.querySelector('ytm-upsell-dialog-renderer');
                if (appPromo) appPromo.remove();
                
                // 2. 가로 차량 화면에 맞춘 컴팩트 뷰 & 시크바 터치 영역 확장 CSS
                var existingStyle = document.getElementById('autotube-car-style');
                if (!existingStyle) {
                    var style = document.createElement('style');
                    style.id = 'autotube-car-style';
                    style.innerHTML = `
                        .mobile-topbar-header { height: 42px !important; }
                        ytm-pivot-bar-renderer { height: 44px !important; }
                        .pivot-bar-item-tab { padding: 4px 0 !important; font-size: 11px !important; }
                        ytm-chip-cloud-renderer { padding: 4px 6px !important; }

                        /* 하단 플레이 타임 바(시크바) 터치 영역 대폭 확대 및 시인성 강화 */
                        ytm-progress-bar, .ytp-progress-bar-container, .progress-bar-line {
                            height: 32px !important;
                            min-height: 32px !important;
                            padding: 12px 0 !important;
                            cursor: pointer !important;
                            touch-action: none !important;
                        }
                        .progress-bar-background {
                            height: 6px !important;
                            border-radius: 3px !important;
                        }
                        .progress-bar-played {
                            height: 6px !important;
                            border-radius: 3px !important;
                            background-color: #ff0000 !important;
                        }
                        .progress-bar-playhead {
                            width: 14px !important;
                            height: 14px !important;
                            border-radius: 50% !important;
                            top: 50% !important;
                            transform: translateY(-50%) !important;
                            background: #ffffff !important;
                            box-shadow: 0 0 3px rgba(0,0,0,0.6) !important;
                        }
                    `;
                    document.head.appendChild(style);
                }

                // 3. 비디오 자동 음소거 해제 보조
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    videos[i].muted = false;
                }

                // 4. 공식 movie_player.seekTo 기반 시크바 정밀 탐색 & 썸네일 클릭 처리
                function doSeek(clientX) {
                    var bar = document.querySelector('ytm-progress-bar, .ytp-progress-bar-container, .progress-bar-line, .progress-bar-background');
                    if (!bar || clientX === undefined) return;
                    var rect = bar.getBoundingClientRect();
                    var ratio = (clientX - rect.left) / rect.width;
                    ratio = Math.max(0, Math.min(1, ratio));

                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    var video = document.querySelector('video');
                    var duration = 0;
                    if (player && typeof player.getDuration === 'function') {
                        duration = player.getDuration();
                    }
                    if (!duration && video) {
                        duration = video.duration;
                    }

                    if (duration > 0) {
                        var targetTime = ratio * duration;
                        if (player && typeof player.seekTo === 'function') {
                            player.seekTo(targetTime, true);
                            if (typeof player.playVideo === 'function') player.playVideo();
                        } else if (video) {
                            video.currentTime = targetTime;
                            video.play();
                        }
                    }
                }

                document.addEventListener('click', function(e) {
                    var target = e.target;
                    // 시크바 영역 클릭
                    var bar = target.closest('ytm-progress-bar, .ytp-progress-bar-container, .progress-bar-line, .progress-bar-background');
                    if (bar) {
                        doSeek(e.clientX);
                        return;
                    }
                    // 썸네일 미리보기 창 클릭
                    var thumb = target.closest('ytm-preview-thumbnail, .ytp-tooltip, [class*="preview"]');
                    if (thumb) {
                        var timeText = thumb.innerText || '';
                        var m = timeText.match(/(\d+):(\d+)(?::(\d+))?/);
                        if (m) {
                            var sec = 0;
                            if (m[3]) {
                                sec = parseInt(m[1])*3600 + parseInt(m[2])*60 + parseInt(m[3]);
                            } else {
                                sec = parseInt(m[1])*60 + parseInt(m[2]);
                            }
                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (player && typeof player.seekTo === 'function') {
                                player.seekTo(sec, true);
                                if (typeof player.playVideo === 'function') player.playVideo();
                            }
                        }
                    }
                }, true);

                // 5. 유튜브 광고 자동 건너뛰기 보조
                function autoSkipAds() {
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton');
                    if (skipBtn) {
                        skipBtn.click();
                    }
                }
                setInterval(autoSkipAds, 1000);

                // 6. 안드로이드 오토 Coolwalk 우측 미디어 카드에 현재 곡명/채널명/재생상태 실시간 전송
                setInterval(function() {
                    try {
                        var titleElem = document.querySelector('.slim-video-information-title, ytm-slim-video-metadata-renderer .title, h2.title, .video-details-title');
                        var title = (titleElem ? titleElem.innerText : '') || document.title.replace(' - YouTube', '').trim();
                        
                        var channelElem = document.querySelector('.slim-owner-channel-name, ytm-channel-name, .channel-name, .ytm-slim-owner-channel-name');
                        var artist = (channelElem ? channelElem.innerText.trim() : '') || 'AutoTube';

                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        var isPlaying = false;
                        if (player && typeof player.getPlayerState === 'function') {
                            isPlaying = (player.getPlayerState() === 1);
                        } else {
                            var v = document.querySelector('video');
                            if (v) isPlaying = (!v.paused && !v.ended && v.currentTime > 0);
                        }

                        if (window.AndroidMedia && title) {
                            if (window.AndroidMedia.onMediaDetailsChanged) {
                                window.AndroidMedia.onMediaDetailsChanged(title, artist, isPlaying);
                            } else {
                                window.AndroidMedia.onMediaStateChanged(title, isPlaying);
                            }
                        }
                    } catch(err) {}
                }, 2000);
            })();
        """.trimIndent()

        view?.evaluateJavascript(carOptimizingJs, null)

        // 진단: 웹뷰 내부 뷰포트와 DPR, 뷰 크기 로그
        view?.evaluateJavascript("(innerWidth+'x'+innerHeight+' dpr='+devicePixelRatio+' vv='+(window.visualViewport?visualViewport.scale:'n/a'))") { r ->
            @Suppress("DEPRECATION")
            android.util.Log.d("WebViewDiag", "css viewport=$r, viewSize=${view.width}x${view.height}, scale=${view.scale}, density=${view.resources.displayMetrics.density}")
        }
    }
}
