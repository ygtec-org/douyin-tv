package com.douyin.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
    private lateinit var prefs: SharedPreferences
    
    private val douyinUrl = "https://www.douyin.com/"
    private val tag = "DouyinTV"
    
    private var lastKeyEventTime = 0L
    private var isVideoPlaying = false
    private var lastAutoSwitchTime = 0L
    private var videoEndedCount = 0
    private var webViewReady = false

    // 验证码滑动支持
    private var captchaDragging = false
    private var captchaStartX = 0f
    private var captchaStartY = 0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全屏显示 - 增强沉浸式体验
        setupFullScreen()
        
        try {
            setupLayout()
            setupWebView()
            setupCookieManager()
            
            prefs = getSharedPreferences("douyin_tv_prefs", Context.MODE_PRIVATE)
            
            // 恢复Cookie
            CookieHelper.restoreCookies(this, douyinUrl)
            
            webView.loadUrl(douyinUrl)
            
            // 显示提示
            Toast.makeText(this, "正在加载抖音,请稍候...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            showErrorAndRestart("应用启动失败: ${e.message}")
        }
    }

    private fun setupFullScreen() {
        // Android 11+ 使用新的全屏API
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            // 确保WebView获得焦点
            requestFocus()
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
        try {
            // 使用优化工具配置WebView
            WebViewOptimizer.optimizeForTV(webView)

            webView.webViewClient = DouyinWebViewClient()
            webView.webChromeClient = DouyinWebChromeClient()
            
            // 添加JavaScript接口
            webView.addJavascriptInterface(VideoJsInterface(), "AndroidInterface")
            
            // 添加触摸事件处理用于验证码滑动
            setupTouchHandler()
        } catch (e: Exception) {
            Log.e(tag, "setupWebView error", e)
            throw RuntimeException("WebView初始化失败", e)
        }
    }

    private fun setupTouchHandler() {
        webView.setOnTouchListener { _, event ->
            try {
                handleTouchEvent(event)
            } catch (e: Exception) {
                Log.e(tag, "Touch event error", e)
            }
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                captchaDragging = true
                captchaStartX = event.x
                captchaStartY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (captchaDragging) {
                    // 滑动验证码时,传递移动事件到WebView
                    val moveEvent = MotionEvent.obtain(
                        event.downTime,
                        event.eventTime,
                        MotionEvent.ACTION_MOVE,
                        event.x,
                        event.y,
                        event.metaState
                    )
                    webView.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (captchaDragging) {
                    captchaDragging = false
                }
            }
        }
        return false
    }

    private fun setupCookieManager() {
        try {
            cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            Log.e(tag, "CookieManager setup error", e)
        }
    }

    inner class DouyinWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false
        }
        
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(tag, "Page load error: ${error?.description}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "页面加载失败,正在重试...", Toast.LENGTH_SHORT).show()
                    // 延迟后重试
                    lifecycleScope.launch {
                        delay(2000)
                        webView.reload()
                    }
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            webViewReady = false
            // 在页面开始加载时就注入反检测脚本
            try {
                injectAntiDetectionScript()
            } catch (e: Exception) {
                Log.e(tag, "injectAntiDetectionScript error", e)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            webViewReady = true
            
            try {
                // 首先注入反检测脚本
                injectAntiDetectionScript()
                
                // 保存Cookie
                CookieHelper.saveCookies(this@MainActivity, douyinUrl)
                cookieManager.flush()
                
                // 注入自动播放下一个视频的JavaScript
                injectAutoPlayScript()
                
                // 注入验证码滑动支持
                injectCaptchaSupport()
                
                // 优化TV显示的CSS
                injectTVOptimizationCSS()
            } catch (e: Exception) {
                Log.e(tag, "onPageFinished error", e)
            }
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
            if (consoleMessage != null) {
                Log.d(tag, "[WebView] ${consoleMessage.message()}")
            }
            return true
        }
    }

    inner class VideoJsInterface {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                try {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAutoSwitchTime > 3000) {
                        videoEndedCount++
                        if (videoEndedCount >= 1) {
                            lastAutoSwitchTime = currentTime
                            videoEndedCount = 0
                            simulateSwipeUp()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "onVideoEnded error", e)
                }
            }
        }
        
        @JavascriptInterface
        fun onVideoPlaying() {
            isVideoPlaying = true
            videoEndedCount = 0
        }
        
        @JavascriptInterface
        fun onVideoPaused() {
            isVideoPlaying = false
        }
    }

    private fun injectAntiDetectionScript() {
        val script = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: () => false
                    });
                } catch(e) {}
                
                try {
                    Object.defineProperty(navigator, 'platform', {
                        get: () => 'Win32'
                    });
                } catch(e) {}
                
                try {
                    window.chrome = {
                        runtime: {},
                        loadTimes: function() {},
                        csi: function() {},
                        app: {}
                    };
                } catch(e) {}
                
                try {
                    Object.defineProperty(navigator, 'maxTouchPoints', {
                        get: () => 0
                    });
                } catch(e) {}
                
                console.log('Anti-detection script loaded');
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }

    private fun injectCaptchaSupport() {
        val script = """
            (function() {
                // 验证码滑动支持
                // 监听滑块元素并添加鼠标事件支持
                function enableCaptchaSlider() {
                    // 查找滑块元素
                    const sliderBtn = document.querySelector('.secsdk_captcha_drag_button') ||
                                     document.querySelector('[class*="drag-button"]') ||
                                     document.querySelector('[class*="slider"]') ||
                                     document.querySelector('div[style*="position: absolute"]');
                    
                    if (sliderBtn && !sliderBtn.hasAttribute('data-captcha-enabled')) {
                        sliderBtn.setAttribute('data-captcha-enabled', 'true');
                        sliderBtn.style.cursor = 'grab';
                        
                        // 添加鼠标事件模拟触摸事件
                        let isDragging = false;
                        let startX = 0;
                        let startY = 0;
                        
                        sliderBtn.addEventListener('mousedown', function(e) {
                            isDragging = true;
                            startX = e.clientX;
                            startY = e.clientY;
                            
                            // 触发touchstart
                            const touchStart = new TouchEvent('touchstart', {
                                touches: [{ clientX: e.clientX, clientY: e.clientY }],
                                bubbles: true
                            });
                            sliderBtn.dispatchEvent(touchStart);
                        });
                        
                        document.addEventListener('mousemove', function(e) {
                            if (isDragging) {
                                // 触发touchmove
                                const touchMove = new TouchEvent('touchmove', {
                                    touches: [{ clientX: e.clientX, clientY: e.clientY }],
                                    bubbles: true
                                });
                                sliderBtn.dispatchEvent(touchMove);
                            }
                        });
                        
                        document.addEventListener('mouseup', function(e) {
                            if (isDragging) {
                                isDragging = false;
                                // 触发touchend
                                const touchEnd = new TouchEvent('touchend', {
                                    touches: [],
                                    bubbles: true
                                });
                                sliderBtn.dispatchEvent(touchEnd);
                            }
                        });
                        
                        console.log('Captcha slider enabled');
                    }
                }
                
                // 定期检查验证码
                setInterval(enableCaptchaSlider, 500);
                enableCaptchaSlider();
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
                
                function monitorVideo() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            
                            video.addEventListener('playing', function() {
                                currentVideoElement = video;
                                try { AndroidInterface.onVideoPlaying(); } catch(e) {}
                            });
                            
                            video.addEventListener('pause', function() {
                                try { AndroidInterface.onVideoPaused(); } catch(e) {}
                            });
                            
                            video.addEventListener('seeking', function() {
                                isSeekingOperation = true;
                            });
                            
                            video.addEventListener('seeked', function() {
                                const timeRemaining = video.duration - video.currentTime;
                                if (timeRemaining < 0.5 && video.duration >= 3) {
                                    console.log('Seeked near end, will auto-switch when ended');
                                }
                                setTimeout(function() {
                                    isSeekingOperation = false;
                                }, 100);
                            });
                            
                            video.addEventListener('timeupdate', function() {
                                if (!video.paused && video.duration > 0) {
                                    const timeRemaining = video.duration - video.currentTime;
                                    if (timeRemaining < 0.3 && timeRemaining > 0) {
                                        const now = Date.now();
                                        const timeSinceLastEnded = now - lastEndedTime;
                                        const hasMinDuration = video.duration >= 3;
                                        const cooldownPassed = timeSinceLastEnded > 3000;
                                        
                                        if (hasMinDuration && cooldownPassed) {
                                            lastEndedTime = now;
                                            try { AndroidInterface.onVideoEnded(); } catch(e) {}
                                        }
                                    }
                                }
                            });
                            
                            video.addEventListener('ended', function() {
                                const now = Date.now();
                                const timeSinceLastEnded = now - lastEndedTime;
                                const isReallyEnded = video.currentTime >= video.duration - 0.5;
                                const hasMinDuration = video.duration >= 3;
                                const cooldownPassed = timeSinceLastEnded > 3000;
                                
                                if (isReallyEnded && hasMinDuration && cooldownPassed) {
                                    lastEndedTime = now;
                                    try { AndroidInterface.onVideoEnded(); } catch(e) {}
                                }
                            });
                            
                            video.setAttribute('autoplay', 'true');
                            video.setAttribute('playsinline', 'true');
                        }
                    });
                }
                
                setInterval(monitorVideo, 1000);
                monitorVideo();
                
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
                if (document.getElementById('tv-optimize-style')) return;
                
                const style = document.createElement('style');
                style.id = 'tv-optimize-style';
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
                    *:focus {
                        outline: 3px solid #00D9FF !important;
                        outline-offset: 2px !important;
                    }
                `;
                document.head.appendChild(style);
                console.log('TV optimization CSS injected');
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
            // 扩展菜单键支持 - 用于登录验证交互
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                handleMenuKey()
                return true
            }
            // 数字键支持 - 用于验证码输入
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9 -> {
                // 传递数字按键到WebView
                return super.onKeyDown(keyCode, event)
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    // 处理菜单键 - 提供多种登录/验证辅助功能
    private fun handleMenuKey() {
        val script = """
            (function() {
                // 尝试点击登录/验证按钮
                const loginBtn = document.querySelector('[class*="login"]') ||
                                document.querySelector('[class*="Login"]') ||
                                document.querySelector('button[type="submit"]') ||
                                document.querySelector('[data-e2e="login"]');
                if (loginBtn) {
                    loginBtn.click();
                    return 'clicked_login';
                }
                
                // 尝试点击确认/提交按钮
                const submitBtn = document.querySelector('[class*="confirm"]') ||
                                 document.querySelector('[class*="submit"]') ||
                                 document.querySelector('button[type="submit"]');
                if (submitBtn) {
                    submitBtn.click();
                    return 'clicked_submit';
                }
                
                // 尝试点击验证码滑块
                const slider = document.querySelector('.secsdk_captcha_drag_button') ||
                              document.querySelector('[class*="drag-button"]') ||
                              document.querySelector('[class*="slider"]');
                if (slider) {
                    // 自动滑动验证码
                    const rect = slider.getBoundingClientRect();
                    const startX = rect.left + rect.width / 2;
                    const startY = rect.top + rect.height / 2;
                    const endX = window.innerWidth - 100;
                    
                    // 模拟滑动
                    const mouseDown = new MouseEvent('mousedown', { clientX: startX, clientY: startY, bubbles: true });
                    slider.dispatchEvent(mouseDown);
                    
                    setTimeout(function() {
                        const mouseMove = new MouseEvent('mousemove', { clientX: endX, clientY: startY, bubbles: true });
                        document.dispatchEvent(mouseMove);
                    }, 200);
                    
                    setTimeout(function() {
                        const mouseUp = new MouseEvent('mouseup', { clientX: endX, clientY: startY, bubbles: true });
                        document.dispatchEvent(mouseUp);
                    }, 500);
                    
                    return 'auto_slide_captcha';
                }
                
                // 显示菜单提示
                const menuBtn = document.querySelector('[class*="menu"]');
                if (menuBtn) {
                    menuBtn.click();
                    return 'clicked_menu';
                }
                
                return 'no_action';
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Menu key action: $result")
            runOnUiThread {
                when (result?.replace("\"", "")) {
                    "clicked_login" -> Toast.makeText(this, "已点击登录按钮", Toast.LENGTH_SHORT).show()
                    "clicked_submit" -> Toast.makeText(this, "已点击确认按钮", Toast.LENGTH_SHORT).show()
                    "auto_slide_captcha" -> Toast.makeText(this, "已自动滑动验证码", Toast.LENGTH_SHORT).show()
                    "clicked_menu" -> Toast.makeText(this, "已打开菜单", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "按上下键切换视频", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
                
                setTimeout(function() {
                    window.scrollBy(0, window.innerHeight);
                }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

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

    private fun scrollHorizontal(deltaX: Int) {
        val script = "window.scrollBy($deltaX, 0);"
        webView.evaluateJavascript(script, null)
    }

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

    // 显示错误并重启
    private fun showErrorAndRestart(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            delay(2000)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
        } catch (e: Exception) {
            Log.e(tag, "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
            webView.pauseTimers()
            cookieManager.flush()
        } catch (e: Exception) {
            Log.e(tag, "onPause error", e)
        }
    }

    override fun onStop() {
        super.onStop()
        // 保存Cookie
        try {
            CookieHelper.saveCookies(this, douyinUrl)
            cookieManager.flush()
        } catch (e: Exception) {
            Log.e(tag, "onStop cookie save error", e)
        }
    }

    override fun onDestroy() {
        try {
            webView.apply {
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.e(tag, "onDestroy error", e)
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
