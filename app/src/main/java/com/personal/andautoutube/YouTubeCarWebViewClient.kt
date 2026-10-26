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

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrEmpty()) {
            tvUrlDisplay?.text = url
            if (!CarWebViewManager.isApplyingPhoneSyncToCar) {
                CarWebViewManager.onCarUrlChanged(url)
            }
        }
        view?.evaluateJavascript("if (window.__atPush) { window.__atPush(); setTimeout(window.__atPush, 300); }", null)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 10
        url?.let {
            tvUrlDisplay?.text = it
            if (!CarWebViewManager.isApplyingPhoneSyncToCar) {
                CarWebViewManager.onCarUrlChanged(it)
            }
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

        // 차량 화면에 맞춤 스크립트 주입 (가로형 와이드 디스플레이 컴팩트 뷰)
        val carOptimizingJs = """
            (function() {
                // 1. 모바일 유튜브에서 상단 '앱 열기' 유도 배너 제거
                var appPromo = document.querySelector('ytm-upsell-dialog-renderer');
                if (appPromo) appPromo.remove();
                
                // 2. 가로 차량 화면에 맞춘 컴팩트 뷰 CSS (시크바 클릭 왜곡 방지를 위해 시크바 강제 CSS는 제거하고 순정 유지)
                var existingStyle = document.getElementById('autotube-car-style');
                if (!existingStyle) {
                    var style = document.createElement('style');
                    style.id = 'autotube-car-style';
                    style.textContent = `
                        .mobile-topbar-header { height: 42px !important; }
                        ytm-pivot-bar-renderer { height: 44px !important; }
                        .pivot-bar-item-tab { padding: 4px 0 !important; font-size: 11px !important; }
                        ytm-chip-cloud-renderer { padding: 4px 6px !important; }
                    `;
                    document.head.appendChild(style);
                }

                // 3. 비디오 자동 음소거 해제 보조
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    videos[i].muted = false;
                }

                // 4. 유튜브 광고 자동 건너뛰기 보조
                function autoSkipAds() {
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton');
                    if (skipBtn) {
                        skipBtn.click();
                    }
                }
                setInterval(autoSkipAds, 1000);

                // 5. 안드로이드 오토 Coolwalk 우측 미디어 카드에 현재 곡명/채널명/재생상태 실시간 전송
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
                            if (!title) {
                                title = 'YouTube';
                            }
                            var channelElem = document.querySelector('.ytVideoSubtitleViewModelSubtitleContent .ytVideoSubtitleViewModelBoldSubstring') || document.querySelector('.slim-owner-channel-name, ytm-channel-name, .channel-name, .ytm-slim-owner-channel-name');
                            artist = (channelElem ? channelElem.innerText.trim() : '') || 'AutoTube';
                        }

                        var thumbUrl = '';
                        var ogImg = document.querySelector('meta[property="og:image"]');
                        if (ogImg && ogImg.content) {
                            thumbUrl = ogImg.content.trim();
                        }
                        if (!thumbUrl) {
                            var vId = '';
                            var match = location.search.match(/[?&]v=([^&]+)/);
                            if (match) vId = match[1];
                            else if (location.pathname.indexOf('/shorts/') === 0) vId = location.pathname.split('/')[2];
                            if (vId) thumbUrl = 'https://i.ytimg.com/vi/' + vId + '/hqdefault.jpg';
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
                if (window.__atMediaHooked) { clearInterval(window.__atMediaTimer); } else { window.__atMediaHooked = true; ['play','playing','pause','ended','seeked'].forEach(function(t){ document.addEventListener(t, function(){ setTimeout(window.__atPush, 50); }, true); }); ['click','touchend','keydown'].forEach(function(t){ document.addEventListener(t, function(){ [100,500,1200].forEach(function(ms){ setTimeout(function(){ if (window.__atPush) window.__atPush(); }, ms); }); }, true); }); ['waiting','stalled','canplay','emptied','loadeddata'].forEach(function(t){ document.addEventListener(t, function(){ setTimeout(window.__atPush, 100); }, true); }); }
                window.__atPush = __push;
                window.__atMediaTimer = setInterval(__push, 1000);
                __push();
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
