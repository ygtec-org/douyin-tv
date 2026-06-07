package com.douyin.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.douyin.tv.utils.CookieHelper
import com.douyin.tv.utils.WebViewOptimizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var cookieManager: CookieManager
    private lateinit var prefs: SharedPreferences
    
    private val douyinUrl = "https://www.douyin.com/"
    private val tag = "DouyinTV"
    
    // 按键控制
    private var lastKeyEventTime = 0L
    private val KEY_DEBOUNCE_MS = 150L  // 优化按键响应，降低防抖时间
    
    // 视频控制
    private var isVideoPlaying = false
    private var lastAutoSwitchTime = 0L
    private var videoEndedCount = 0
    
    // 页面状态
    private var webViewReady = false
    private var pageLoadAttempts = 0
    private val MAX_LOAD_ATTEMPTS = 3
    
    // 验证码滑动
    private var captchaDragging = false
    
    // 登录状态监控
    private var loginCheckJob: Job? = null
    
    // 内存监控
    private val MEMORY_THRESHOLD_MB = 100  // 内存使用超过此值触发清理
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全屏显示
        setupFullScreen()
        
        try {
            // 初始化WebView(使用Application Context避免内存泄漏)
            setupLayout()
            setupWebView()
            setupCookieManager()
            setupLoadingUI()
            
            prefs = getSharedPreferences("douyin_tv_prefs", Context.MODE_PRIVATE)
            
            // 恢复Cookie
            CookieHelper.restoreCookies(this, douyinUrl)
            
            // 检查登录状态
            checkLoginStatus()
            
            // 加载页面
            webView.loadUrl(douyinUrl)
            
            // 显示提示
            showLoadingMessage("正在加载抖音,请稍候...")
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            showErrorAndRestart("应用启动失败: ${e.message}")
        }
    }
    
    // ========== UI Setup ==========
    
    private fun setupFullScreen() {
        // Android 11+ 使用新的全屏API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.decorView.windowInsetsController
            controller?.let {
                it.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
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
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(applicationContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f  // weight=1, 让WebView占满空间
            )
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8  // 更细的进度条
            )
            max = 100
            visibility = View.GONE
            progressDrawable?.setTint(Color.parseColor("#FE2C55"))  // 抖音红
        }
        
        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        setContentView(rootLayout)
    }
    
    private fun setupLoadingUI() {
        // 创建加载提示覆盖层
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#CC000000"))  // 半透明黑色
            id = View.generateViewId()
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER
        }
        
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FE2C55"))
        }
        
        loadingText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            text = "正在加载..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        
        content.addView(progressBar)
        content.addView(loadingText)
        overlay.addView(content)
        
        (rootView as ViewGroup).addView(overlay)
    }
    
    private val rootView: View
        get() = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
    
    private fun showLoadingMessage(message: String) {
        runOnUiThread {
            loadingText.text = message
            // 3秒后隐藏加载提示
            lifecycleScope.launch {
                delay(3000)
                try {
                    (rootView as? ViewGroup)?.let { parent ->
                        val overlay = parent.findViewById<View?>(View.generateViewId())
                        overlay?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }
    
    // ========== WebView Setup ==========
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            WebViewOptimizer.optimizeForTV(webView, applicationContext)

            webView.webViewClient = DouyinWebViewClient()
            webView.webChromeClient = DouyinWebChromeClient()
            
            // 添加JavaScript接口
            webView.addJavascriptInterface(VideoJsInterface(), "AndroidInterface")
            
            // 添加触摸事件处理用于验证码滑动
            setupTouchHandler()
            
            // 添加内存监控
            setupMemoryMonitor()
        } catch (e: Exception) {
            Log.e(tag, "setupWebView error", e)
            throw RuntimeException("WebView初始化失败", e)
        }
    }
    
    private fun setupMemoryMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30000)  // 每30秒检查一次
                checkMemoryUsage()
            }
        }
    }
    
    private fun checkMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        if (usedMemory > MEMORY_THRESHOLD_MB) {
            Log.d(tag, "High memory usage: ${usedMemory}MB, triggering cleanup")
            webView.post {
                try {
                    webView.clearCache(false)  // 清除内存缓存
                } catch (e: Exception) {
                    Log.e(tag, "clearCache error", e)
                }
            }
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
            }
            MotionEvent.ACTION_MOVE -> {
                if (captchaDragging) {
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
                captchaDragging = false
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
    
    // ========== WebViewClient & WebChromeClient ==========
    
    inner class DouyinWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false  // 允许所有URL加载
        }
        
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            // 拦截不必要的资源请求(低配设备)
            if (WebViewOptimizer.getDeviceLevel(this@MainActivity) == WebViewOptimizer.DeviceLevel.LOW) {
                val url = request?.url?.toString() ?: return null
                // 拦截广告和统计请求
                if (url.contains("ad") || url.contains("analytics") || url.contains("tracking")) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
            }
            return null
        }
        
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(tag, "Page load error: ${error?.description}")
                pageLoadAttempts++
                
                if (pageLoadAttempts <= MAX_LOAD_ATTEMPTS) {
                    runOnUiThread {
                        showLoadingMessage("页面加载失败,正在重试...(${pageLoadAttempts}/$MAX_LOAD_ATTEMPTS)")
                        lifecycleScope.launch {
                            delay(2000L * pageLoadAttempts)  // 递增延迟
                            webView.reload()
                        }
                    }
                }
            }
        }
        
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            webViewReady = false
            pageLoadAttempts = 0
            showLoadingMessage("正在加载抖音...")
            
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
                // 保存Cookie
                CookieHelper.saveCookies(this@MainActivity, douyinUrl)
                cookieManager.flush()
                
                // 注入脚本
                injectAntiDetectionScript()
                injectAutoPlayScript()
                injectCaptchaSupport()
                injectLoginDetectionScript()
                injectTVOptimizationCSS()
                
                // 检查登录状态
                checkLoginStatus()
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
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, "[WebView] ${consoleMessage.message()}")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, "[WebView] ${consoleMessage.message()}")
                    else -> Log.d(tag, "[WebView] ${consoleMessage.message()}")
                }
            }
            return true
        }
    }
    
    // ========== JavaScript Interfaces ==========
    
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
        
        @JavascriptInterface
        fun onLoginStatusChanged(isLoggedIn: Boolean) {
            runOnUiThread {
                if (isLoggedIn) {
                    showLoadingMessage("登录成功,正在加载...")
                } else {
                    Toast.makeText(this@MainActivity, "检测到登录弹窗,请使用手机扫码", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // ========== Script Injection ==========
    
    private fun injectAntiDetectionScript() {
        val script = """
            (function() {
                try { Object.defineProperty(navigator, 'webdriver', { get: () => false }); } catch(e) {}
                try { Object.defineProperty(navigator, 'platform', { get: () => 'Win32' }); } catch(e) {}
                try {
                    window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
                } catch(e) {}
                try { Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 }); } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
    
    private fun injectCaptchaSupport() {
        val script = """
            (function() {
                function enableCaptchaSlider() {
                    const sliderBtn = document.querySelector('.secsdk_captcha_drag_button') ||
                                     document.querySelector('[class*="drag-button"]') ||
                                     document.querySelector('[class*="slider"]');
                    
                    if (sliderBtn && !sliderBtn.hasAttribute('data-captcha-enabled')) {
                        sliderBtn.setAttribute('data-captcha-enabled', 'true');
                        sliderBtn.style.cursor = 'grab';
                        
                        let isDragging = false;
                        
                        sliderBtn.addEventListener('mousedown', function(e) {
                            isDragging = true;
                            const touchStart = new TouchEvent('touchstart', {
                                touches: [{ clientX: e.clientX, clientY: e.clientY, identifier: 0 }],
                                bubbles: true
                            });
                            sliderBtn.dispatchEvent(touchStart);
                        });
                        
                        document.addEventListener('mousemove', function(e) {
                            if (isDragging) {
                                const touchMove = new TouchEvent('touchmove', {
                                    touches: [{ clientX: e.clientX, clientY: e.clientY, identifier: 0 }],
                                    bubbles: true
                                });
                                sliderBtn.dispatchEvent(touchMove);
                            }
                        });
                        
                        document.addEventListener('mouseup', function(e) {
                            if (isDragging) {
                                isDragging = false;
                                const touchEnd = new TouchEvent('touchend', {
                                    touches: [],
                                    bubbles: true
                                });
                                sliderBtn.dispatchEvent(touchEnd);
                            }
                        });
                    }
                }
                
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
                let isSeekingOperation = false;
                
                function monitorVideo() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            
                            video.addEventListener('playing', function() {
                                try { AndroidInterface.onVideoPlaying(); } catch(e) {}
                            });
                            
                            video.addEventListener('pause', function() {
                                try { AndroidInterface.onVideoPaused(); } catch(e) {}
                            });
                            
                            video.addEventListener('seeking', function() {
                                isSeekingOperation = true;
                            });
                            
                            video.addEventListener('seeked', function() {
                                setTimeout(function() { isSeekingOperation = false; }, 100);
                            });
                            
                            video.addEventListener('timeupdate', function() {
                                if (!video.paused && video.duration > 0) {
                                    const timeRemaining = video.duration - video.currentTime;
                                    if (timeRemaining < 0.3 && timeRemaining > 0) {
                                        const now = Date.now();
                                        if (video.duration >= 3 && (now - lastEndedTime) > 3000) {
                                            lastEndedTime = now;
                                            try { AndroidInterface.onVideoEnded(); } catch(e) {}
                                        }
                                    }
                                }
                            });
                            
                            video.addEventListener('ended', function() {
                                const now = Date.now();
                                const isReallyEnded = video.currentTime >= video.duration - 0.5;
                                if (isReallyEnded && video.duration >= 3 && (now - lastEndedTime) > 3000) {
                                    lastEndedTime = now;
                                    try { AndroidInterface.onVideoEnded(); } catch(e) {}
                                }
                            });
                            
                            video.setAttribute('autoplay', 'true');
                            video.setAttribute('playsinline', 'true');
                            video.setAttribute('preload', 'auto');
                        }
                    });
                }
                
                setInterval(monitorVideo, 1000);
                monitorVideo();
                
                // 自动播放第一个视频
                setTimeout(function() {
                    const firstVideo = document.querySelector('video');
                    if (firstVideo) {
                        firstVideo.muted = false;
                        firstVideo.play().catch(e => console.log('Autoplay prevented:', e));
                    }
                }, 1500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
    
    private fun injectLoginDetectionScript() {
        val script = """
            (function() {
                // 检测登录弹窗
                function checkLoginModal() {
                    const loginModal = document.querySelector('[class*="login"]') ||
                                      document.querySelector('[class*="Login"]') ||
                                      document.querySelector('[class*="modal"]');
                    
                    if (loginModal && loginModal.offsetParent !== null) {
                        console.log('Login modal detected');
                        try { AndroidInterface.onLoginStatusChanged(false); } catch(e) {}
                    }
                }
                
                // 检测登录成功
                function checkLoginSuccess() {
                    const userAvatar = document.querySelector('[class*="avatar"]') ||
                                      document.querySelector('[data-e2e="user-avatar"]');
                    
                    if (userAvatar) {
                        try { AndroidInterface.onLoginStatusChanged(true); } catch(e) {}
                    }
                }
                
                setInterval(checkLoginModal, 2000);
                setInterval(checkLoginSuccess, 3000);
                
                // 首次检测
                setTimeout(checkLoginModal, 3000);
                setTimeout(checkLoginSuccess, 5000);
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
                    * { cursor: none !important; }
                    body { overflow: hidden; }
                    video {
                        width: 100vw !important;
                        height: 100vh !important;
                        object-fit: contain !important;
                        background: #000;
                    }
                    *:focus {
                        outline: 3px solid #00D9FF !important;
                        outline-offset: 2px !important;
                    }
                    /* 隐藏干扰元素 */
                    .header, .nav, .sidebar { display: none !important; }
                    /* 优化视频容器 */
                    .player-container, .video-player {
                        width: 100% !important;
                        height: 100% !important;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(css, null)
    }
    
    // ========== Remote Control ==========
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 防抖动处理
        if (currentTime - lastKeyEventTime < KEY_DEBOUNCE_MS) {
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                handleMenuKey()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                toggleVideoPlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playVideo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pauseVideo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                simulateSwipeUp()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                simulateSwipeDown()
                return true
            }
            // 数字键支持
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9 -> {
                return super.onKeyDown(keyCode, event)
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
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
                
                // 自动滑动验证码
                const slider = document.querySelector('.secsdk_captcha_drag_button') ||
                              document.querySelector('[class*="drag-button"]') ||
                              document.querySelector('[class*="slider"]');
                if (slider) {
                    const rect = slider.getBoundingClientRect();
                    const startX = rect.left + rect.width / 2;
                    const startY = rect.top + rect.height / 2;
                    const endX = window.innerWidth - 100;
                    
                    const mouseDown = new MouseEvent('mousedown', { clientX: startX, clientY: startY, bubbles: true });
                    slider.dispatchEvent(mouseDown);
                    
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mousemove', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 200);
                    
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mouseup', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 500);
                    
                    return 'auto_slide_captcha';
                }
                
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
    
    // ========== Video Control ==========
    
    private fun simulateSwipeUp() {
        val script = """
            (function() {
                document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
                setTimeout(function() { window.scrollBy(0, window.innerHeight); }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
    
    private fun simulateSwipeDown() {
        val script = """
            (function() {
                document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', keyCode: 38, which: 38, bubbles: true }));
                setTimeout(function() { window.scrollBy(0, -window.innerHeight); }, 100);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
    
    private fun scrollHorizontal(deltaX: Int) {
        webView.evaluateJavascript("window.scrollBy($deltaX, 0);", null)
    }
    
    private fun toggleVideoPlayPause() {
        val script = """
            (function() {
                const video = document.querySelector('video');
                if (video) { video.paused ? video.play() : video.pause(); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }
    
    private fun playVideo() {
        val script = "(function() { const v = document.querySelector('video'); if (v && v.paused) v.play(); })();"
        webView.evaluateJavascript(script, null)
    }
    
    private fun pauseVideo() {
        val script = "(function() { const v = document.querySelector('video'); if (v && !v.paused) v.pause(); })();"
        webView.evaluateJavascript(script, null)
    }
    
    // ========== Login Status ==========
    
    private fun checkLoginStatus() {
        lifecycleScope.launch {
            delay(3000)  // 等待页面加载
            val isLoggedIn = CookieHelper.isLoggedIn(douyinUrl)
            if (!isLoggedIn) {
                Log.d(tag, "Not logged in, waiting for user to scan QR code")
                showLoadingMessage("请使用手机抖音扫码登录")
            } else {
                Log.d(tag, "Already logged in")
            }
        }
    }
    
    // ========== Lifecycle ==========
    
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
            CookieHelper.saveCookies(this, douyinUrl)
        } catch (e: Exception) {
            Log.e(tag, "onPause error", e)
        }
    }
    
    override fun onStop() {
        super.onStop()
        loginCheckJob?.cancel()
    }
    
    override fun onDestroy() {
        loginCheckJob?.cancel()
        try {
            webView.apply {
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
                clearHistory()
                clearCache(true)
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
