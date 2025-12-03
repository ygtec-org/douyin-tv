package com.douyin.tv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.douyin.tv.utils.CookieHelper
import com.douyin.tv.utils.WebViewOptimizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var cookieManager: CookieManager
    
    private val douyinUrl = "https://www.douyin.com/"
    private var lastKeyEventTime = 0L
    private var isVideoPlaying = false
    private var lastAutoSwitchTime = 0L  // 记录上次自动切换时间
    private var videoEndedCount = 0  // 记录ended事件触发次数

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全屏显示
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        setupLayout()
        setupWebView()
        setupCookieManager()
        
        // 恢复Cookie
        CookieHelper.restoreCookies(this, douyinUrl)
        
        webView.loadUrl(douyinUrl)
        
        // 显示提示
        Toast.makeText(this, "正在加载抖音,请稍候...", Toast.LENGTH_SHORT).show()
    }

    private fun setupLayout() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                20
            )
            max = 100
            visibility = View.GONE
        }

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        setContentView(rootLayout)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // 使用优化工具配置WebView
        WebViewOptimizer.optimizeForTV(webView)

        webView.webViewClient = DouyinWebViewClient()
        webView.webChromeClient = DouyinWebChromeClient()
        
        // 添加JavaScript接口用于自动播放下一个视频
        webView.addJavascriptInterface(VideoJsInterface(), "AndroidInterface")
    }

    private fun setupCookieManager() {
        cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    inner class DouyinWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false
        }
        
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // 在页面开始加载时就注入反检测脚本
            injectAntiDetectionScript()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            
            // 首先注入反检测脚本
            injectAntiDetectionScript()
            
            // 保存Cookie
            CookieHelper.saveCookies(this@MainActivity, douyinUrl)
            cookieManager.flush()
            
            // 注入自动播放下一个视频的JavaScript
            injectAutoPlayScript()
            
            // 优化TV显示的CSS
            injectTVOptimizationCSS()
        }
    }

    inner class DouyinWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = newProgress
            } else {
                progressBar.visibility = View.GONE
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            return true
        }
    }

    inner class VideoJsInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                val currentTime = System.currentTimeMillis()
                // 防止频繁触发:至少间隔3秒才能自动切换
                if (currentTime - lastAutoSwitchTime > 3000) {
                    videoEndedCount++
                    // 只有ended事件被确认触发时才切换
                    if (videoEndedCount >= 1) {
                        lastAutoSwitchTime = currentTime
                        videoEndedCount = 0
                        // 视频结束时自动播放下一个
                        simulateSwipeUp()
                    }
                }
            }
        }
        
        @JavascriptInterface
        fun onVideoPlaying() {
            isVideoPlaying = true
            videoEndedCount = 0  // 重置计数器
        }
        
        @JavascriptInterface
        fun onVideoPaused() {
            isVideoPlaying = false
        }
    }

    // 反检测脚本 - 隐藏WebView特征
    private fun injectAntiDetectionScript() {
        val script = """
            (function() {
                // 隐藏__firefox__和__chrome__变量
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: () => false
                    });
                } catch(e) {}
                
                // 隐藏WebView标识
                try {
                    Object.defineProperty(navigator, 'platform', {
                        get: () => 'Win32'
                    });
                } catch(e) {}
                
                // 模拟Chrome扩展
                try {
                    window.chrome = {
                        runtime: {},
                        loadTimes: function() {},
                        csi: function() {},
                        app: {}
                    };
                } catch(e) {}
                
                // 隐藏AndroidInterface暴露
                const originalAndroidInterface = window.AndroidInterface;
                delete window.AndroidInterface;
                window.__hiddenAndroidInterface = originalAndroidInterface;
                
                // 恢复AndroidInterface的安全访问
                window.AndroidInterface = new Proxy({}, {
                    get: function(target, prop) {
                        return window.__hiddenAndroidInterface[prop];
                    }
                });
                
                // 阻止检测触摸事件
                Object.defineProperty(navigator, 'maxTouchPoints', {
                    get: () => 0
                });
                
                console.log('Anti-detection script injected successfully');
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }

    private fun injectAutoPlayScript() {
        val script = """
            (function() {
                let lastEndedTime = 0;
                let currentVideoElement = null;
                let isSeekingOperation = false;
                
                // 监听视频播放状态
                function monitorVideo() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            
                            // 视频开始播放时
                            video.addEventListener('playing', function() {
                                currentVideoElement = video;
                                AndroidInterface.onVideoPlaying();
                                console.log('Video playing, duration:', video.duration);
                            });
                            
                            // 视频暂停时
                            video.addEventListener('pause', function() {
                                AndroidInterface.onVideoPaused();
                            });
                            
                            // 监听快进/快退操作
                            video.addEventListener('seeking', function() {
                                isSeekingOperation = true;
                                console.log('Video seeking to:', video.currentTime);
                            });
                            
                            video.addEventListener('seeked', function() {
                                console.log('Video seeked to:', video.currentTime, 'duration:', video.duration);
                                // 快进/快退后,检查是否接近结尾
                                const timeRemaining = video.duration - video.currentTime;
                                if (timeRemaining < 0.5 && video.duration >= 3) {
                                    console.log('Seeked near end, will auto-switch when ended');
                                }
                                // 重置seeking标记(延迟一点,让ended事件能检测到)
                                setTimeout(function() {
                                    isSeekingOperation = false;
                                }, 100);
                            });
                            
                            // 监听播放进度 - 持续检测是否到达结尾
                            video.addEventListener('timeupdate', function() {
                                if (!video.paused && video.duration > 0) {
                                    const timeRemaining = video.duration - video.currentTime;
                                    // 如果播放到最后0.3秒,触发自动切换
                                    if (timeRemaining < 0.3 && timeRemaining > 0) {
                                        const now = Date.now();
                                        const timeSinceLastEnded = now - lastEndedTime;
                                        const hasMinDuration = video.duration >= 3;
                                        const cooldownPassed = timeSinceLastEnded > 3000;
                                        
                                        if (hasMinDuration && cooldownPassed) {
                                            lastEndedTime = now;
                                            console.log('Video near end (timeupdate), auto switching...');
                                            AndroidInterface.onVideoEnded();
                                        }
                                    }
                                }
                            });
                            
                            // 视频结束时 - 增加严格检查
                            video.addEventListener('ended', function() {
                                const now = Date.now();
                                const timeSinceLastEnded = now - lastEndedTime;
                                
                                // 检查视频是否真的播放完了
                                const isReallyEnded = video.currentTime >= video.duration - 0.5;
                                // 视频时长至少3秒(避免极短视频频繁切换)
                                const hasMinDuration = video.duration >= 3;
                                // 距离上次ended事件至少3秒
                                const cooldownPassed = timeSinceLastEnded > 3000;
                                
                                console.log('Video ended event:', {
                                    isReallyEnded: isReallyEnded,
                                    hasMinDuration: hasMinDuration,
                                    cooldownPassed: cooldownPassed,
                                    isSeekingOperation: isSeekingOperation,
                                    currentTime: video.currentTime,
                                    duration: video.duration
                                });
                                
                                // 快进/快退到结尾也要支持自动切换
                                if (isReallyEnded && hasMinDuration && cooldownPassed) {
                                    lastEndedTime = now;
                                    console.log('Auto switching to next video (ended event)...');
                                    AndroidInterface.onVideoEnded();
                                } else {
                                    console.log('Skipped auto switch - conditions not met');
                                }
                            });
                            
                            // 自动播放
                            video.setAttribute('autoplay', 'true');
                            video.setAttribute('playsinline', 'true');
                        }
                    });
                }
                
                // 定期检查新视频
                setInterval(monitorVideo, 1000);
                monitorVideo();
                
                // 自动播放第一个视频
                setTimeout(function() {
                    const firstVideo = document.querySelector('video');
                    if (firstVideo) {
                        firstVideo.muted = false;
                        firstVideo.play().catch(e => console.log('Autoplay prevented:', e));
                    }
                }, 2000);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }

    private fun injectTVOptimizationCSS() {
        val css = """
            (function() {
                const style = document.createElement('style');
                style.innerHTML = `
                    * {
                        cursor: none !important;
                    }
                    body {
                        overflow: hidden;
                    }
                    video {
                        width: 100vw !important;
                        height: 100vh !important;
                        object-fit: contain !important;
                    }
                    /* 隐藏不必要的UI元素 */
                    .header, .nav, .sidebar {
                        display: none !important;
                    }
                    /* 优化焦点显示 */
                    *:focus {
                        outline: 3px solid #00D9FF !important;
                        outline-offset: 2px !important;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(css, null)
    }

    // 电视遥控器按键处理
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 防抖动处理
        if (currentTime - lastKeyEventTime < 200) {
            return true
        }
        lastKeyEventTime = currentTime

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                simulateSwipeDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                simulateSwipeUp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                scrollHorizontal(-200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                scrollHorizontal(200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleVideoPlayPause()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                showMenu()
                return true
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }

    // 模拟向上滑动(切换到下一个视频)
    private fun simulateSwipeUp() {
        val script = """
            (function() {
                const event = new KeyboardEvent('keydown', {
                    key: 'ArrowDown',
                    keyCode: 40,
                    which: 40,
                    bubbles: true
                });
                document.dispatchEvent(event);
                
                // 尝试点击下一个视频
                setTimeout(function() {
                    window.scrollBy(0, window.innerHeight);
                }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // 模拟向下滑动(切换到上一个视频)
    private fun simulateSwipeDown() {
        val script = """
            (function() {
                const event = new KeyboardEvent('keydown', {
                    key: 'ArrowUp',
                    keyCode: 38,
                    which: 38,
                    bubbles: true
                });
                document.dispatchEvent(event);
                
                setTimeout(function() {
                    window.scrollBy(0, -window.innerHeight);
                }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // 水平滚动
    private fun scrollHorizontal(deltaX: Int) {
        val script = "window.scrollBy($deltaX, 0);"
        webView.evaluateJavascript(script, null)
    }

    // 播放/暂停切换
    private fun toggleVideoPlayPause() {
        val script = """
            (function() {
                const video = document.querySelector('video');
                if (video) {
                    if (video.paused) {
                        video.play();
                    } else {
                        video.pause();
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // 显示菜单
    private fun showMenu() {
        val script = """
            (function() {
                // 触发抖音的菜单
                const menuBtn = document.querySelector('[class*="menu"]');
                if (menuBtn) {
                    menuBtn.click();
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        
        // 保存Cookie
        cookieManager.flush()
    }

    override fun onDestroy() {
        webView.apply {
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
