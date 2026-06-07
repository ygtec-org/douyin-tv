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
import androidx.annotation.RequiresApi
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

    private val douyinUrl = "https://www.douyin.com/jingxuan?from_nav=1"
    private val tag = "DouyinTV"

    // 按键控制
    private var lastKeyEventTime = 0L
    private val KEY_DEBOUNCE_MS = 120L

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
    private val MEMORY_THRESHOLD_MB = 100

    private var overlayId = -1
    private var overlay: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullScreen()
        try {
            setupLayout()
            setupWebView()
            setupCookieManager()
            setupLoadingUI()
            prefs = getSharedPreferences("douyin_tv_prefs", Context.MODE_PRIVATE)
            CookieHelper.restoreCookies(this, douyinUrl)
            webView.loadUrl(douyinUrl)
            showLoadingMessage("正在加载抖音,请稍候...")
            lifecycleScope.launch {
                delay(5000)
                hideLoadingOverlay()
            }
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            showErrorAndRestart("应用启动失败: ${e.message}")
        }
    }

    private fun setupFullScreen() {
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

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            // 关键：不让 WebView 持有焦点，防止它处理方向键
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8
            )
            max = 100
            visibility = View.GONE
            progressDrawable?.setTint(Color.parseColor("#FE2C55"))
        }

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        setContentView(rootLayout)
    }

    private fun setupLoadingUI() {
        overlayId = View.generateViewId()
        overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#CC000000"))
            id = overlayId
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER
        }

        val pb = ProgressBar(this).apply {
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

        content.addView(pb)
        content.addView(loadingText)
        overlay?.addView(content)

        (rootView as? ViewGroup)?.addView(overlay)
    }

    private fun hideLoadingOverlay() {
        runOnUiThread { overlay?.visibility = View.GONE }
    }

    private val rootView: View
        get() = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    private fun showLoadingMessage(message: String) {
        runOnUiThread { loadingText.text = message }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            WebViewOptimizer.optimizeForTV(webView, applicationContext)
            webView.webViewClient = DouyinWebViewClient()
            webView.webChromeClient = DouyinWebChromeClient()
            webView.addJavascriptInterface(VideoJsInterface(), "AndroidInterface")
            setupTouchHandler()
            setupMemoryMonitor()
        } catch (e: Exception) {
            Log.e(tag, "setupWebView error", e)
            throw RuntimeException("WebView初始化失败", e)
        }
    }

    private fun setupMemoryMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30000)
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
                try { webView.clearCache(false) } catch (e: Exception) { Log.e(tag, "clearCache error", e) }
            }
        }
    }

    private fun setupTouchHandler() {
        webView.setOnTouchListener { _, event ->
            try { handleTouchEvent(event) } catch (e: Exception) { Log.e(tag, "Touch event error", e) }
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { captchaDragging = true }
            MotionEvent.ACTION_MOVE -> {
                if (captchaDragging) {
                    val moveEvent = MotionEvent.obtain(
                        event.downTime, event.eventTime, MotionEvent.ACTION_MOVE,
                        event.x, event.y, event.metaState
                    )
                    webView.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { captchaDragging = false }
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
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (WebViewOptimizer.getDeviceLevel(this@MainActivity) == WebViewOptimizer.DeviceLevel.LOW) {
                val url = request?.url?.toString() ?: return null
                if (url.contains("ad") || url.contains("analytics") || url.contains("tracking")) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
            }
            return null
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(tag, "Page load error: ${error?.description}")
                pageLoadAttempts++
                if (pageLoadAttempts <= MAX_LOAD_ATTEMPTS) {
                    runOnUiThread {
                        showLoadingMessage("页面加载失败,正在重试...(${pageLoadAttempts}/$MAX_LOAD_ATTEMPTS)")
                        lifecycleScope.launch {
                            delay(2000L * pageLoadAttempts)
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
            try { injectAntiDetectionScript() } catch (e: Exception) { Log.e(tag, "injectAntiDetectionScript error", e) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            webViewReady = true
            hideLoadingOverlay()
            try {
                CookieHelper.saveCookies(this@MainActivity, douyinUrl)
                cookieManager.flush()
                injectAntiDetectionScript()
                injectAutoPlayScript()
                injectCaptchaSupport()
                injectLoginDetectionScript()
                injectTVOptimizationCSS()
                checkLoginStatus()
                lifecycleScope.launch {
                    delay(3000); initFocusEngine()
                }
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

    inner class VideoJsInterface {
        @JavascriptInterface
        fun onVideoEnded() { isVideoPlaying = false }

        @JavascriptInterface
        fun onVideoPlaying() { isVideoPlaying = true; videoEndedCount = 0 }

        @JavascriptInterface
        fun onVideoPaused() { isVideoPlaying = false }

        @JavascriptInterface
        fun onLoginStatusChanged(isLoggedIn: Boolean) {
            runOnUiThread {
                if (isLoggedIn) showLoadingMessage("登录成功,正在加载...")
                else Toast.makeText(this@MainActivity, "检测到登录弹窗,请使用手机扫码", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun injectAntiDetectionScript() {
        val script = """
            (function() {
                try { Object.defineProperty(navigator, 'webdriver', { get: () => false }); } catch(e) {}
                try { Object.defineProperty(navigator, 'platform', { get: () => 'Win32' }); } catch(e) {}
                try { window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} }; } catch(e) {}
                try { Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 }); } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectCaptchaSupport() {
        val script = """
            (function() {
                function createTouch(target, x, y) {
                    try {
                        return new Touch({
                            identifier: 0,
                            target: target,
                            clientX: x,
                            clientY: y,
                            pageX: x,
                            pageY: y,
                            screenX: x,
                            screenY: y
                        });
                    } catch(e) { return null; }
                }
                function dispatchTouch(target, type, x, y) {
                    try {
                        var touch = createTouch(target, x, y);
                        if (!touch) return;
                        var ev = new TouchEvent(type, {
                            touches: type === 'touchend' ? [] : [touch],
                            targetTouches: type === 'touchend' ? [] : [touch],
                            changedTouches: [touch],
                            bubbles: true,
                            cancelable: true
                        });
                        target.dispatchEvent(ev);
                    } catch(e) {}
                }
                function enableCaptchaSlider() {
                    const sliderBtn = document.querySelector('.secsdk_captcha_drag_button') ||
                                     document.querySelector('[class*="drag-button"]');
                    if (sliderBtn && !sliderBtn.hasAttribute('data-captcha-enabled')) {
                        sliderBtn.setAttribute('data-captcha-enabled', 'true');
                        sliderBtn.style.cursor = 'grab';
                        let isDragging = false;
                        sliderBtn.addEventListener('mousedown', function(e) {
                            isDragging = true;
                            dispatchTouch(sliderBtn, 'touchstart', e.clientX, e.clientY);
                        });
                        document.addEventListener('mousemove', function(e) {
                            if (isDragging) dispatchTouch(sliderBtn, 'touchmove', e.clientX, e.clientY);
                        });
                        document.addEventListener('mouseup', function() {
                            if (isDragging) {
                                isDragging = false;
                                dispatchTouch(sliderBtn, 'touchend', 0, 0);
                            }
                        });
                    }
                }
                setInterval(enableCaptchaSlider, 1000);
                enableCaptchaSlider();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectAutoPlayScript() {
        val script = """
            (function() {
                function ensureSoundEnabled(video) {
                    try {
                        video.muted = false;
                        if (isFinite(video.volume)) video.volume = 1.0;
                    } catch(e) {}
                }
                function monitorVideo() {
                    document.querySelectorAll('video').forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            ensureSoundEnabled(video);
                            video.addEventListener('loadedmetadata', function() { ensureSoundEnabled(video); });
                            video.addEventListener('playing', function() {
                                try { AndroidInterface.onVideoPlaying(); } catch(e) {}
                            });
                            video.addEventListener('pause', function() {
                                try { AndroidInterface.onVideoPaused(); } catch(e) {}
                            });
                            video.setAttribute('autoplay', 'true');
                            video.setAttribute('playsinline', 'true');
                            video.setAttribute('preload', 'auto');
                        }
                    });
                }
                const observer = new MutationObserver(function() { monitorVideo(); });
                observer.observe(document.body, { childList: true, subtree: true });
                setInterval(monitorVideo, 2000);
                monitorVideo();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectLoginDetectionScript() {
        val script = """
            (function() {
                let hasNotifiedLogin = false;
                function checkLoginModal() {
                    const loginModal = document.querySelector('[class*="login-modal"]') ||
                                      document.querySelector('[class*="LoginModal"]') ||
                                      document.querySelector('[class*="loginDialog"]') ||
                                      document.querySelector('[data-e2e="login-modal"]');
                    if (loginModal && loginModal.offsetParent !== null && !hasNotifiedLogin) {
                        hasNotifiedLogin = true;
                        try { AndroidInterface.onLoginStatusChanged(false); } catch(e) {}
                    }
                }
                function checkLoginSuccess() {
                    const userAvatar = document.querySelector('[data-e2e="user-avatar"]') ||
                                      document.querySelector('[class*="user-avatar"]');
                    if (userAvatar) {
                        hasNotifiedLogin = false;
                        try { AndroidInterface.onLoginStatusChanged(true); } catch(e) {}
                    }
                }
                setTimeout(function() {
                    setInterval(checkLoginModal, 5000);
                    setInterval(checkLoginSuccess, 5000);
                }, 8000);
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
                    *:focus { outline: none !important; }
                    video:-webkit-full-screen,
                    video:fullscreen {
                        width: 100vw !important;
                        height: 100vh !important;
                        object-fit: contain !important;
                        background: #000;
                    }
                    *, *::before, *::after {
                        animation-duration: 0.01ms !important;
                        transition-duration: 0.05ms !important;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(css, null)
    }

    /**
     * 拦截方向键（仅DPAD/OK），其他键正常传递。BACK 不拦截，避免阻断系统退出。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    return onKeyDown(event.keyCode, event)
                }
                // BACK 走默认流程，由 Activity 的 onKeyDown 处理
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastKeyEventTime < KEY_DEBOUNCE_MS) return true
        lastKeyEventTime = currentTime

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { focusEngineNavigate(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { focusEngineNavigate(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { focusEngineNavigate(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { focusEngineNavigate(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { focusEngineAction(); return true }
            KeyEvent.KEYCODE_BACK -> {
                // 优先：尝试退出全屏 → 内部页面回退 → 系统默认（退出App）
                tryExitVideoFullscreen()
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                // 返回 false 让系统处理 BACK（退出App）
                return false
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { handleMenuKey(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggleVideoPlayPause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { playVideo(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pauseVideo(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * TV 焦点引擎：2D 空间最近邻导航
     */
    private fun initFocusEngine() {
        if (!webViewReady) return
        val script = """
            (function() {
                console.log('[TV] initFocusEngine called');
                if (window.__tvFocus) {
                    try { window.__tvFocus.cleanup(); } catch(e) {}
                }
                var TV = window.__tvFocus = {};

                // 高亮框
                var oldHl = document.getElementById('tv-hl-box');
                if (oldHl) oldHl.remove();
                var hl = document.createElement('div');
                hl.id = 'tv-hl-box';
                hl.style.cssText = [
                    'position:fixed','top:0','left:0','width:0','height:0',
                    'border:4px solid #FE2C55','border-radius:8px',
                    'box-shadow:0 0 16px rgba(254,44,85,1)',
                    'pointer-events:none','z-index:2147483647',
                    'transition:transform 0.15s ease-out, width 0.15s, height 0.15s',
                    'display:none','background:transparent','box-sizing:border-box',
                    'transform:translate(0,0)','will-change:transform,width,height'
                ].join(';');
                document.body.appendChild(hl);
                TV.hl = hl;

                // 状态提示
                var oldLabel = document.getElementById('tv-hl-label');
                if (oldLabel) oldLabel.remove();
                var label = document.createElement('div');
                label.id = 'tv-hl-label';
                label.style.cssText = [
                    'position:fixed','top:8px','left:50%','transform:translateX(-50%)',
                    'background:rgba(254,44,85,0.95)','color:#fff','padding:5px 14px',
                    'border-radius:14px','font-size:13px','z-index:2147483647',
                    'pointer-events:none','display:none','font-family:sans-serif',
                    'max-width:70vw','white-space:nowrap','overflow:hidden',
                    'text-overflow:ellipsis','font-weight:bold'
                ].join(';');
                document.body.appendChild(label);
                TV.label = label;

                function showLabel(text) {
                    label.textContent = text;
                    label.style.display = 'block';
                    clearTimeout(TV.labelTimer);
                    TV.labelTimer = setTimeout(function() { label.style.display = 'none'; }, 1500);
                }

                // 元素可见性 - 阈值降低
                function isVisible(el) {
                    if (!el || !document.body.contains(el)) return false;
                    var r = el.getBoundingClientRect();
                    if (r.width < 10 || r.height < 10) return false;
                    if (r.bottom < 0 || r.top > window.innerHeight) return false;
                    if (r.right < 0 || r.left > window.innerWidth) return false;
                    var st = window.getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                    return true;
                }

                function makeItem(el, type, name) {
                    var r = el.getBoundingClientRect();
                    return {
                        el: el, type: type,
                        name: (name || '').toString().trim().substring(0, 30),
                        x: r.left + r.width / 2,
                        y: r.top + r.height / 2,
                        l: r.left, t: r.top,
                        r: r.right, b: r.bottom,
                        w: r.width, h: r.height
                    };
                }

                // 缓存：仅在 DOM 变化时刷新
                var gatherVersion = 0;
                var lastGatherTime = 0;
                function gather() {
                    var items = [];
                    var seen = new Set();
                    function add(el, type, name) {
                        if (!el || seen.has(el) || !isVisible(el)) return;
                        seen.add(el);
                        items.push(makeItem(el, type, name));
                    }
                    // 视频卡片：先找 [data-aweme-id] 容器内的 <a>（实际可点击的链接元素）
                    document.querySelectorAll('[data-aweme-id]').forEach(function(el) {
                        var link = el.querySelector('a');
                        var target = link || el;
                        var titleEl = el.querySelector('.z72L2AHI') ||
                                      el.querySelector('[class*="title"]') ||
                                      el.querySelector('img[alt]') ||
                                      el.querySelector('[class*="desc"]');
                        var name = titleEl ?
                            (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
                        add(target, 'video', name);
                    });
                    // 兜底：用 <a> 内有 /video/ 或 /note/ 路径的链接（无需 data-aweme-id）
                    if (items.length === 0) {
                        document.querySelectorAll('a[href*="/video/"], a[href*="/note/"]').forEach(function(link) {
                            if (!isVisible(link)) return;
                            var r = link.getBoundingClientRect();
                            if (r.width < 10 || r.height < 10) return;
                            if (seen.has(link)) return;
                            seen.add(link);
                            var container = link.closest('[data-aweme-id]') || link.closest('section') || link.closest('[class*="feed"]') || link;
                            var titleEl = link.querySelector('img[alt]') || link.querySelector('[class*="title"]') || link.querySelector('[class*="desc"]');
                            var name = titleEl ?
                                (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
                            items.push(makeItem(link, 'video', name));
                        });
                    }
                    // 顶部 tab：支持 role="tab" 以及半糖 UI 组件的 tab
                    document.querySelectorAll('[role="tab"]').forEach(function(el) {
                        var name = (el.textContent || '').trim();
                        if (name) add(el, 'toptab', name);
                    });
                    // 兜底：顶部导航链接（.semi-tabs-tab 类名的 tab 元素）
                    if (items.filter(function(i){return i.type==='toptab'}).length === 0) {
                        document.querySelectorAll('.semi-tabs-tab, .web-nav-wrapper a, .NavBar-Container a').forEach(function(el) {
                            var name = (el.textContent || '').trim();
                            if (name && name.length < 20) add(el, 'toptab', name);
                        });
                    }
                    // 左侧菜单：.tab-discover 包裹的 <a>（不是 .tab-discover div 本身）
                    document.querySelectorAll('.tab-discover a').forEach(function(link) {
                        var nameEl = link.querySelector('.Ou5DxC0w') || link.querySelector('span');
                        var name = nameEl ? nameEl.textContent.trim() : (link.textContent.trim() || '菜单');
                        add(link, 'leftnav', name);
                    });
                    // 兜底：左侧导航链接
                    if (items.filter(function(i){return i.type==='leftnav'}).length === 0) {
                        document.querySelectorAll('.sidebar-container a, [class*="Sidebar"] a, [class*="side-nav"] a').forEach(function(el) {
                            var name = (el.textContent || '').trim();
                            if (name && name.length < 20) add(el, 'leftnav', name);
                        });
                    }

                    TV.items = items;
                    var vCount = items.filter(function(i){return i.type==='video'}).length;
                    var tCount = items.filter(function(i){return i.type==='toptab'}).length;
                    var lCount = items.filter(function(i){return i.type==='leftnav'}).length;
                    if (vCount !== TV._lastVideoCount || tCount !== TV._lastTabCount || lCount !== TV._lastNavCount) {
                        gatherVersion++;
                        TV._lastVideoCount = vCount;
                        TV._lastTabCount = tCount;
                        TV._lastNavCount = lCount;
                        console.log('[TV] gather v' + gatherVersion + ': video=' + vCount + ' tab=' + tCount + ' nav=' + lCount);
                    }
                    return items.length;
                }
                TV.refresh = gather;

                // 轻量版 gather：只更新 current 坐标，不重新查询 DOM
                function gatherLight() {
                    if (!TV.items) return;
                    var changed = false;
                    for (var i = 0; i < TV.items.length; i++) {
                        var it = TV.items[i];
                        if (!it.el || !document.body.contains(it.el)) continue;
                        var r = it.el.getBoundingClientRect();
                        if (r.width === 0 || r.height === 0) continue;
                        var nx = r.left + r.width / 2;
                        var ny = r.top + r.height / 2;
                        if (Math.abs(nx - it.x) > 2 || Math.abs(ny - it.y) > 2) {
                            it.x = nx; it.y = ny;
                            it.l = r.left; it.t = r.top;
                            it.r = r.right; it.b = r.bottom;
                            it.w = r.width; it.h = r.height;
                            changed = true;
                        }
                    }
                    if (changed) gatherVersion++;
                    return gatherVersion;
                }
                TV.gatherLight = gatherLight;

                function drawBox() {
                    if (!TV.current) { hl.style.display = 'none'; return; }
                    var el = TV.current.el;
                    if (!el || !document.body.contains(el)) { hl.style.display = 'none'; return; }
                    var r = el.getBoundingClientRect();
                    if (r.width < 5 || r.height < 5) { hl.style.display = 'none'; return; }
                    // 实时更新 current 的坐标
                    TV.current.x = r.left + r.width / 2;
                    TV.current.y = r.top + r.height / 2;
                    TV.current.l = r.left; TV.current.t = r.top;
                    TV.current.r = r.right; TV.current.b = r.bottom;
                    TV.current.w = r.width; TV.current.h = r.height;
                    hl.style.display = 'block';
                    hl.style.width = (r.width + 8) + 'px';
                    hl.style.height = (r.height + 8) + 'px';
                    hl.style.transform = 'translate(' + (r.left - 4) + 'px,' + (r.top - 4) + 'px)';
                    var typeLabel = { leftnav:'菜单', toptab:'分类', video:'视频' };
                    showLabel('▶ ' + (typeLabel[TV.current.type] || '') + ': ' + TV.current.name);
                }
                TV.draw = drawBox;

                // 2D 空间最近邻：在 (dx,dy) 方向找最近元素
                // 用扇形权重：方向上的距离 + 偏离方向的距离 * 2
                function findNearestInDirection(cur, dx, dy) {
                    if (!TV.items || TV.items.length === 0) return null;
                    // 实时刷新 cur 位置
                    var curR = cur.el.getBoundingClientRect();
                    var cx = curR.left + curR.width / 2;
                    var cy = curR.top + curR.height / 2;

                    var best = null;
                    var bestScore = Infinity;

                    for (var i = 0; i < TV.items.length; i++) {
                        var it = TV.items[i];
                        if (it.el === cur.el) continue;
                        if (!document.body.contains(it.el)) continue;
                        var r = it.el.getBoundingClientRect();
                        if (r.width === 0 || r.height === 0) continue;
                        var ix = r.left + r.width / 2;
                        var iy = r.top + r.height / 2;
                        var ddx = ix - cx;
                        var ddy = iy - cy;

                        // 方向过滤：只需轻微偏移
                        if (dy > 0) {
                            if (ddy < curR.height * 0.15) continue;
                        } else if (dy < 0) {
                            if (ddy > -curR.height * 0.15) continue;
                        } else if (dx > 0) {
                            if (ddx < curR.width * 0.15) continue;
                        } else if (dx < 0) {
                            if (ddx > -curR.width * 0.15) continue;
                        }

                        // 评分：主方向距离 + 偏移方向距离 * 2
                        var primary, secondary;
                        if (dy !== 0) {
                            primary = Math.abs(ddy);
                            secondary = Math.abs(ddx);
                        } else {
                            primary = Math.abs(ddx);
                            secondary = Math.abs(ddy);
                        }
                        // 偏离方向超过屏幕一半就放弃
                        if (dy !== 0 && secondary > window.innerWidth * 0.6) continue;
                        if (dx !== 0 && secondary > window.innerHeight * 0.6) continue;

                        var score = primary + secondary * 1.5;
                        if (score < bestScore) {
                            bestScore = score;
                            best = it;
                        }
                    }
                    return best;
                }

                function moveFocus(dx, dy) {
                    // 轻量更新：只刷新坐标，不重新查询 DOM
                    gatherLight();

                    if (!TV.items || TV.items.length === 0) {
                        window.scrollBy(0, dy * 200);
                        return false;
                    }

                    var cur = TV.current;
                    if (cur && (!document.body.contains(cur.el) || !isVisible(cur.el))) {
                        cur = null;
                        TV.current = null;
                    }

                    if (!cur) {
                        var cx = window.innerWidth / 2;
                        var cy = window.innerHeight / 2;
                        var best = null, bestD = Infinity;
                        TV.items.forEach(function(it) {
                            var d = Math.hypot(it.x - cx, it.y - cy);
                            if (d < bestD) { bestD = d; best = it; }
                        });
                        if (best) {
                            TV.current = best;
                            best.el.scrollIntoView({ block:'center', inline:'nearest' });
                            setTimeout(drawBox, 60);
                        }
                        return true;
                    }

                    var next = findNearestInDirection(cur, dx, dy);

                    if (!next) {
                        // 没找到：如果是向下且当前是视频，尝试滚动
                        if (dy > 0 && cur.type === 'video') {
                            window.scrollBy({ top: window.innerHeight * 0.7, behavior: 'auto' });
                            setTimeout(function() {
                                gather();
                                gatherLight();
                                var nxt = findNearestInDirection(TV.current, 0, 1);
                                if (nxt) {
                                    TV.current = nxt;
                                    nxt.el.scrollIntoView({ block:'center' });
                                    setTimeout(drawBox, 60);
                                } else {
                                    drawBox();
                                }
                            }, 500);
                            return true;
                        }
                        drawBox();
                        return false;
                    }

                    TV.current = next;
                    next.el.scrollIntoView({ block:'nearest', inline:'nearest' });
                    setTimeout(drawBox, 60);
                    return true;
                }
                TV.move = moveFocus;

                function activate() {
                    if (!TV.current) { moveFocus(0, 0); return 'init'; }
                    var item = TV.current;
                    var el = item.el;
                    if (!document.body.contains(el)) {
                        console.log('[TV] current el detached, refresh & reselect');
                        TV.current = null;
                        moveFocus(0, 0);
                        return 'reset';
                    }
                    console.log('[TV] activate ' + item.type + ': ' + item.name);

                    if (item.type === 'video') {
                        // 视频卡片：直接在目标元素上 click()（不需要 dispatchEvent 链）
                        // 抖音 SPA 路由靠 click 事件触发，直接 click() 最可靠
                        try { el.click(); } catch(e) {}
                        showLabel('▶ 打开: ' + item.name);
                        // 页面跳转后重置焦点
                        setTimeout(function() {
                            TV.current = null;
                        }, 1500);
                        return 'video-clicked';
                    }

                    // 顶部 tab / 左侧菜单
                    var inner = el.querySelector('a') || el;
                    var r2 = inner.getBoundingClientRect();
                    var x2 = r2.left + r2.width / 2, y2 = r2.top + r2.height / 2;
                    try { inner.click(); } catch(e) {}

                    showLabel('✓ ' + item.name);
                    // 分类切换后，等 DOM 刷新后自动跳到第一个视频
                    setTimeout(function() {
                        gather();
                        gatherLight();
                        var videos = TV.items.filter(function(i) { return i.type === 'video'; });
                        if (videos.length > 0 && (item.type === 'leftnav' || item.type === 'toptab')) {
                            TV.current = null;
                        }
                    }, 500);
                    return 'clicked';
                }
                TV.activate = activate;

                // 节流的 DOM 变化监听
                var refreshTimer;
                var observer = new MutationObserver(function() {
                    clearTimeout(refreshTimer);
                    refreshTimer = setTimeout(function() {
                        gather();
                        if (TV.current && document.body.contains(TV.current.el)) {
                            drawBox();
                        }
                    }, 500);
                });
                observer.observe(document.body, { childList:true, subtree:true });
                TV.observer = observer;

                var scrollTimer;
                var scrollHandler = function() {
                    clearTimeout(scrollTimer);
                    scrollTimer = setTimeout(function() {
                        if (TV.current) drawBox();
                    }, 16);
                };
                window.addEventListener('scroll', scrollHandler, { passive:true, capture:true });
                document.addEventListener('scroll', scrollHandler, { passive:true, capture:true });
                TV.scrollHandler = scrollHandler;

                // 定期全量刷新（每 5 秒），保证新出现的视频卡片被加入
                setInterval(function() { gather(); }, 5000);

                window.addEventListener('resize', function() {
                    gather();
                    if (TV.current) drawBox();
                });

                TV.cleanup = function() {
                    try { observer.disconnect(); } catch(e) {}
                    try { window.removeEventListener('scroll', scrollHandler); } catch(e) {}
                    try { document.removeEventListener('scroll', scrollHandler); } catch(e) {}
                    try { hl.remove(); label.remove(); } catch(e) {}
                };

                // 启动
                gather();
                setTimeout(function() {
                    moveFocus(0, 0);
                }, 300);

                return 'ok:' + TV.items.length;
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Focus engine init: $result")
        }
    }

    private fun focusEngineNavigate(dx: Int, dy: Int) {
        val script = "if(window.__tvFocus){window.__tvFocus.move($dx,$dy)}else{window.scrollBy(0,${if (dy > 0) "window.innerHeight*0.6" else "-window.innerHeight*0.6"})};"
        webView.evaluateJavascript(script, null)
    }

    private fun focusEngineAction() {
        val script = """
            (function() {
                if (window.__tvFocus) {
                    var result = window.__tvFocus.activate();
                    return result;
                }
                // 如果已经在视频播放页，尝试全屏
                var href = window.location.href;
                if (href.indexOf('/video/') > -1 || href.indexOf('/note/') > -1) {
                    try {
                        if (!document.fullscreenElement) {
                            document.documentElement.requestFullscreen();
                            return 'fullscreen-entered';
                        } else {
                            document.exitFullscreen();
                            return 'fullscreen-exited';
                        }
                    } catch(e) {}
                }
                return 'no-engine';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Focus action: $result")
        }
    }

    private fun tryExitVideoFullscreen(): Boolean {
        val script = """
            (function() {
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                    return 'exited';
                }
                return 'no-fullscreen';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        return false
    }

    private fun handleMenuKey() {
        val script = """
            (function() {
                const loginBtn = document.querySelector('[class*="login"]') ||
                                document.querySelector('[class*="Login"]') ||
                                document.querySelector('button[type="submit"]') ||
                                document.querySelector('[data-e2e="login"]');
                if (loginBtn) { loginBtn.click(); return 'clicked_login'; }
                const slider = document.querySelector('.secsdk_captcha_drag_button') ||
                              document.querySelector('[class*="drag-button"]') ||
                              document.querySelector('[class*="slider"]');
                if (slider) {
                    const rect = slider.getBoundingClientRect();
                    const startX = rect.left + rect.width / 2;
                    const startY = rect.top + rect.height / 2;
                    const endX = window.innerWidth - 100;
                    slider.dispatchEvent(new MouseEvent('mousedown', { clientX: startX, clientY: startY, bubbles: true }));
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mousemove', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 200);
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mouseup', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 500);
                    return 'auto_slide_captcha';
                }
                return 'no_action';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Menu key action: $result")
            runOnUiThread {
                when (result?.replace("\"", "")) {
                    "clicked_login" -> Toast.makeText(this, "已点击登录按钮", Toast.LENGTH_SHORT).show()
                    "auto_slide_captcha" -> Toast.makeText(this, "已自动滑动验证码", Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
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
        webView.evaluateJavascript("(function() { const v = document.querySelector('video'); if (v && v.paused) v.play(); })();", null)
    }

    private fun pauseVideo() {
        webView.evaluateJavascript("(function() { const v = document.querySelector('video'); if (v && !v.paused) v.pause(); })();", null)
    }

    private fun checkLoginStatus() {
        lifecycleScope.launch {
            delay(3000)
            val isLoggedIn = CookieHelper.isLoggedIn(douyinUrl)
            if (!isLoggedIn) {
                Log.d(tag, "Not logged in, waiting for user to scan QR code")
                showLoadingMessage("请使用手机抖音扫码登录")
            } else {
                Log.d(tag, "Already logged in")
            }
        }
    }

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
