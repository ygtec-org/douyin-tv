package com.douyin.tv

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DouyinTV"
        private const val DOUYIN_URL = "https://www.douyin.com/jingxuan?from_nav=1"

        private const val PREF_FILE = "douyin_tv_prefs"
        private const val PREF_ORIENTATION = "orientation"
        private const val PREF_COMPAT = "compat_mode"
        private const val PREF_HINT_SHOWN = "hint_shown"

        private const val ORIENT_LANDSCAPE = "landscape"
        private const val ORIENT_PORTRAIT = "portrait"
        private const val ORIENT_AUTO = "auto"

        private const val MAX_LOAD_ATTEMPTS = 2
        private const val LOAD_TIMEOUT_MS = 20_000L

        private val ACCENT = Color.parseColor("#FE2C55")
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var prefs: SharedPreferences

    private var webView: WebView? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val loadTimeout = Runnable {
        if (!webViewReady && loadingOverlay.visibility == View.VISIBLE) {
            showLoading("加载较慢…按 OK 重试，或按菜单键打开设置")
        }
    }

    @Volatile private var webViewReady = false
    private var pageLoadAttempts = 0
    private var lastVisitedUrl: String? = null

    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isPseudoVideoFullscreen = false
    @Volatile private var captchaVisible = false
    @Volatile private var isVideoPlaying = false

    private var loadStartedAt = 0L
    private var menuVisible = false
    private var menuIndex = 0
    private var menuActions: List<Pair<String, () -> Unit>> = emptyList()
    private val menuItemViews = mutableListOf<TextView>()

    private var lastLoginHintAt = 0L
    private var lastCaptchaHintAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        setupFullScreen()
        buildUi()
        applyOrientation()

        try {
            createWebView()
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                webView?.let { setAcceptThirdPartyCookies(it, true) }
            }
            CookieHelper.restoreCookies(this, DOUYIN_URL)
            loadHome()
            showFirstRunHint()
            warnIfWebViewOutdated()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate failed", t)
            showFatalError("应用启动失败：${t.message}")
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun setupFullScreen() {
        window.setBackgroundDrawableResource(android.R.color.black)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    /**
     * 根布局必须是 FrameLayout：旧版把 WebView / 进度条 / 加载层都塞进竖向 LinearLayout，
     * 加载层 match_parent 会吃掉全部高度，WebView 权重高度变成 0，表现为“蓝屏/黑屏转圈打不开”。
     */
    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(
            rootLayout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
            )
        }
        rootLayout.addView(progressBar)

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6000000"))
            isClickable = true
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val loadingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        loadingText = TextView(this).apply {
            text = "正在加载抖音，请稍候..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        loadingContent.addView(spinner)
        loadingContent.addView(loadingText)
        loadingOverlay.addView(loadingContent)
        rootLayout.addView(loadingOverlay)

        errorOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2000000"))
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(40), 0, dp(40), dp(14))
        }
        val errorHint = TextView(this).apply {
            text = "按 OK 重试 · 按返回键退出 · 按菜单键打开设置"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        errorOverlay.addView(errorText)
        errorOverlay.addView(errorHint)
        rootLayout.addView(errorOverlay)

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(fullscreenContainer)

        menuOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F2141414"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                dp(340),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(36) }
        }
        menuOverlay.addView(menuPanel)
        rootLayout.addView(menuOverlay)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        destroyWebView()
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        WebViewOptimizer.optimizeForTV(wv, applicationContext, isCompatMode())
        wv.webViewClient = TvWebViewClient()
        wv.webChromeClient = TvWebChromeClient()
        wv.addJavascriptInterface(TvJsInterface(), "AndroidInterface")
        webView = wv
        rootLayout.addView(wv, 0)
        wv.requestFocus()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.webChromeClient = null
            wv.webViewClient = WebViewClient()
            wv.removeJavascriptInterface("AndroidInterface")
            wv.loadUrl("about:blank")
            wv.removeAllViews()
            wv.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroyWebView failed", t)
        }
    }

    private fun loadHome() {
        pageLoadAttempts = 0
        webViewReady = false
        loadStartedAt = SystemClock.uptimeMillis()
        hideError()
        showLoading("正在加载抖音，请稍候...")
        webView?.loadUrl(DOUYIN_URL)
        uiHandler.removeCallbacks(loadTimeout)
        uiHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
    }

    private fun retryLoad() {
        pageLoadAttempts = 0
        loadHome()
    }

    private fun isCompatMode(): Boolean =
        prefs.getBoolean(PREF_COMPAT, WebViewOptimizer.isWebViewOutdated(this))

    private inner class TvWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            webViewReady = false
            captchaVisible = false
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            showLoading("正在加载抖音...")
            lastVisitedUrl = url
            injectAntiDetection()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            hideLoading()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            webViewReady = true
            pageLoadAttempts = 0
            uiHandler.removeCallbacks(loadTimeout)
            hideLoading()
            progressBar.visibility = View.GONE
            webView?.requestFocus()

            lifecycleScope.launch(Dispatchers.IO) {
                CookieHelper.saveCookies(this@MainActivity, DOUYIN_URL)
            }

            injectAntiDetection()
            injectTvCss()
            injectAutoPlay()
            injectCaptchaSupport()
            injectLoginWatch()
            checkLoginStatus()

            // 页面首屏视频渲染完成后再挂焦点引擎，避免与首帧解码抢主线程
            uiHandler.postDelayed({ scheduleFocusEngine() }, 1600)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (url != null && url != lastVisitedUrl) {
                lastVisitedUrl = url
                if (!isReload) {
                    uiHandler.postDelayed({ scheduleFocusEngine() }, 1200)
                }
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != true) return
            onMainFrameError(error?.description?.toString() ?: "网络错误")
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (failingUrl == null || failingUrl == view?.url) {
                onMainFrameError(description ?: "网络错误")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) {
                onMainFrameError("服务器错误 ${errorResponse?.statusCode}")
            }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            Log.e(TAG, "Render process gone, didCrash=${detail?.didCrash()}")
            if (view === webView) {
                destroyWebView()
                showLoading("页面渲染进程已重启，正在重新加载...")
                uiHandler.postDelayed({
                    try {
                        createWebView()
                        loadHome()
                    } catch (t: Throwable) {
                        Log.e(TAG, "recreate webview failed", t)
                        showFatalError("WebView 重启失败：${t.message}")
                    }
                }, 400)
            }
            // 返回 true 表示已自行处理，避免系统直接杀掉整个应用
            return true
        }
    }

    private fun onMainFrameError(message: String) {
        uiHandler.post {
            pageLoadAttempts++
            if (pageLoadAttempts <= MAX_LOAD_ATTEMPTS) {
                showLoading("页面加载失败，正在重试（$pageLoadAttempts/$MAX_LOAD_ATTEMPTS）...")
                uiHandler.postDelayed({ webView?.reload() }, 1500L * pageLoadAttempts)
            } else {
                showError("页面加载失败：$message")
            }
        }
    }

    private inner class TvWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress in 0..99) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = newProgress
            } else {
                progressBar.visibility = View.GONE
            }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            if (customVideoView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customVideoView = view
            customViewCallback = callback
            // 直接往 decorView 加视图在部分电视上会失败，导致视频层不可见；这里用独立容器
            (view.parent as? ViewGroup)?.removeView(view)
            webView?.visibility = View.GONE
            fullscreenContainer.removeAllViews()
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
            isPseudoVideoFullscreen = false
            setupFullScreen()
            fullscreenContainer.requestFocus()
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null) {
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR ->
                        Log.e(TAG, "[WebView] ${consoleMessage.message()}")
                    ConsoleMessage.MessageLevel.WARNING ->
                        Log.w(TAG, "[WebView] ${consoleMessage.message()}")
                    else -> {}
                }
            }
            return true
        }
    }

    private fun hideCustomView() {
        val view = customVideoView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        customVideoView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView?.visibility = View.VISIBLE
        webView?.requestFocus()
    }

    // ---------------------------------------------------------------- JS bridge

    inner class TvJsInterface {
        @JavascriptInterface
        fun onVideoPlaying() {
            isVideoPlaying = true
        }

        @JavascriptInterface
        fun onVideoPaused() {
            isVideoPlaying = false
        }

        @JavascriptInterface
        fun onVideoEnded() {
            isVideoPlaying = false
        }

        @JavascriptInterface
        fun onVideoFullscreenChanged(fullscreen: Boolean) {
            isPseudoVideoFullscreen = fullscreen
        }

        @JavascriptInterface
        fun onLoginStatusChanged(loggedIn: Boolean) {
            runOnUiThread { if (loggedIn) toast("已登录") else showLoginHint() }
        }

        @JavascriptInterface
        fun onCaptchaVisible(visible: Boolean) {
            runOnUiThread {
                captchaVisible = visible
                if (visible) showCaptchaHint()
            }
        }
    }

    // ---------------------------------------------------------------- keys

    private fun isRemoteKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_PROG_RED,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
        else -> false
    }

    /**
     * 统一在 dispatchKeyEvent 处理，避免 onKeyDown / OnKeyListener 重复消费导致丢键。
     * 不再做按键去抖：连按方向键是 TV 上最常见的操作，旧逻辑 120ms 内直接丢弃会让遥控器像失灵。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!isRemoteKey(code)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true

        if (code == KeyEvent.KEYCODE_BACK) return handleBack()

        if (menuVisible) return handleMenuKey(code)
        if (errorOverlay.visibility == View.VISIBLE) return handleErrorKey(code)
        return handleMainKey(code, event)
    }

    private fun handleBack(): Boolean {
        if (menuVisible) {
            hideMenu()
            return true
        }
        if (customVideoView != null) {
            hideCustomView()
            return true
        }
        if (isPseudoVideoFullscreen) {
            exitPseudoFullscreen()
            return true
        }
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return true
        }
        return false
    }

    private fun handleErrorKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            retryLoad()
            true
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
            showMenu()
            true
        }
        else -> false
    }

    private fun handleMainKey(code: Int, event: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> {
                showMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 长按 OK 在任何页面都能唤出设置，作为“菜单键失灵”的兜底入口
                if (event.repeatCount >= 1) {
                    showMenu()
                    return true
                }
                if (captchaVisible) {
                    evaluate("window.__tvCaptcha && window.__tvCaptcha.confirm();")
                    return true
                }
                if (!webViewReady) {
                    // 加载中按 OK 不立刻刷新，避免连续重载；超过 8 秒仍无响应才重试
                    if (SystemClock.uptimeMillis() - loadStartedAt > 8000) retryLoad()
                    return true
                }
                focusEngineAction()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (captchaVisible) nudgeCaptcha(-1) else focusEngineNavigate(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (captchaVisible) nudgeCaptcha(1) else focusEngineNavigate(1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusEngineNavigate(0, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusEngineNavigate(0, 1)
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
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                focusEngineNavigate(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                focusEngineNavigate(1, 0)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        if (menuVisible) return
        rebuildMenu(0)
        menuOverlay.visibility = View.VISIBLE
        menuVisible = true
        webView?.clearFocus()
    }

    private fun hideMenu() {
        menuOverlay.visibility = View.GONE
        menuVisible = false
        if (customVideoView != null) fullscreenContainer.requestFocus() else webView?.requestFocus()
    }

    private fun rebuildMenu(selected: Int) {
        menuActions = buildMenuEntries()
        menuItemViews.clear()
        menuPanel.removeAllViews()

        val title = TextView(this).apply {
            text = "设置"
            setTextColor(Color.WHITE)
            textSize = 12f
            letterSpacing = 0.2f
            setPadding(dp(14), 0, dp(14), dp(10))
        }
        menuPanel.addView(title)

        menuActions.forEachIndexed { index, entry ->
            val item = TextView(this).apply {
                text = entry.first
                textSize = 17f
                setTextColor(Color.parseColor("#DDDDDD"))
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                setOnClickListener {
                    menuIndex = index
                    updateMenuSelection()
                    entry.second.invoke()
                }
            }
            menuItemViews.add(item)
            menuPanel.addView(item)
        }

        val hint = TextView(this).apply {
            val webViewVersion = WebViewOptimizer.getWebViewMajorVersion(this@MainActivity)
            val versionText = if (webViewVersion > 0) "WebView Chrome/$webViewVersion" else "WebView 版本未知"
            text = "方向键选择 · OK 确认 · 返回键关闭\n$versionText"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(dp(14), dp(12), dp(14), 0)
        }
        menuPanel.addView(hint)

        menuIndex = selected.coerceIn(0, maxOf(0, menuActions.size - 1))
        updateMenuSelection()
    }

    private fun updateMenuSelection() {
        menuItemViews.forEachIndexed { index, view ->
            val selected = index == menuIndex
            view.setBackgroundColor(if (selected) ACCENT else Color.TRANSPARENT)
            view.setTextColor(if (selected) Color.WHITE else Color.parseColor("#DDDDDD"))
        }
    }

    private fun handleMenuKey(code: Int): Boolean {
        if (menuActions.isEmpty()) {
            hideMenu()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                menuIndex = (menuIndex - 1 + menuActions.size) % menuActions.size
                updateMenuSelection()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                menuIndex = (menuIndex + 1) % menuActions.size
                updateMenuSelection()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                menuActions.getOrNull(menuIndex)?.second?.invoke()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> {
                hideMenu()
                return true
            }
        }
        return false
    }

    private fun buildMenuEntries(): List<Pair<String, () -> Unit>> = listOf(
        "扫码登录（手机抖音扫码）" to ::actionScanLogin,
        (if (isFullscreenActive()) "退出全屏播放" else "全屏播放当前视频") to ::actionToggleFullscreen,
        "画面方向：${orientationLabel()}" to ::actionCycleOrientation,
        "兼容模式（手机网页）：${if (isCompatMode()) "开" else "关"}" to ::actionToggleCompat,
        "刷新页面" to ::actionReload,
        "清除缓存 / 退出登录" to ::actionClearCache,
        "退出应用" to ::actionExit
    )

    private fun actionScanLogin() {
        hideMenu()
        val script = """
            (function() {
                var el = document.querySelector('[data-e2e="login-button"]') ||
                         document.querySelector('[class*="login-button"]') ||
                         document.querySelector('[class*="LoginButton"]') ||
                         document.querySelector('button[class*="login"]') ||
                         document.querySelector('[class*="login"]');
                if (!el) return 'no-entry';
                el.click();
                return 'ok';
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            if (result == null || !result.contains("ok")) {
                toast("未找到登录入口，请先刷新页面，再按菜单键重试")
            }
        }
        toast("请使用手机抖音扫码登录")
    }

    private fun actionToggleFullscreen() {
        hideMenu()
        when {
            customVideoView != null -> hideCustomView()
            isPseudoVideoFullscreen -> exitPseudoFullscreen()
            else -> {
                evaluate("window.__tvFocus && window.__tvFocus.playPrimary && window.__tvFocus.playPrimary();")
                toast("已尝试全屏播放，按返回键退出全屏")
            }
        }
    }

    private fun actionCycleOrientation() {
        val next = when (prefs.getString(PREF_ORIENTATION, ORIENT_LANDSCAPE)) {
            ORIENT_LANDSCAPE -> ORIENT_PORTRAIT
            ORIENT_PORTRAIT -> ORIENT_AUTO
            else -> ORIENT_LANDSCAPE
        }
        prefs.edit().putString(PREF_ORIENTATION, next).apply()
        applyOrientation()
        rebuildMenu(menuIndex)
    }

    private fun actionToggleCompat() {
        val enabled = !isCompatMode()
        prefs.edit().putBoolean(PREF_COMPAT, enabled).apply()
        webView?.let { WebViewOptimizer.applyUserAgent(it, this, enabled) }
        hideMenu()
        toast(if (enabled) "已启用兼容模式，正在重新加载" else "已关闭兼容模式，正在重新加载")
        loadHome()
    }

    private fun actionReload() {
        hideMenu()
        retryLoad()
    }

    private fun actionClearCache() {
        hideMenu()
        CookieHelper.clearAllCookies(this)
        webView?.let { WebViewOptimizer.clearCache(it) }
        toast("已清除缓存与登录状态")
        retryLoad()
    }

    private fun actionExit() {
        finishAffinity()
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.getString(PREF_ORIENTATION, ORIENT_LANDSCAPE)) {
            ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ORIENT_AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun orientationLabel(): String = when (prefs.getString(PREF_ORIENTATION, ORIENT_LANDSCAPE)) {
        ORIENT_PORTRAIT -> "竖屏"
        ORIENT_AUTO -> "自动旋转"
        else -> "横屏"
    }

    private fun isFullscreenActive(): Boolean = customVideoView != null || isPseudoVideoFullscreen

    // ---------------------------------------------------------------- overlays

    private fun showLoading(message: String) {
        uiHandler.post {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
            errorOverlay.visibility = View.GONE
        }
    }

    private fun hideLoading() {
        uiHandler.post { loadingOverlay.visibility = View.GONE }
    }

    private fun showError(message: String) {
        uiHandler.post {
            progressBar.visibility = View.GONE
            loadingOverlay.visibility = View.GONE
            errorText.text = message
            errorOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideError() {
        uiHandler.post { errorOverlay.visibility = View.GONE }
    }

    private fun showFatalError(message: String) {
        uiHandler.post {
            loadingOverlay.visibility = View.GONE
            errorText.text = message
            errorOverlay.visibility = View.VISIBLE
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "toast failed: $message", t)
            }
        }
    }

    private fun showCaptchaHint() {
        val now = System.currentTimeMillis()
        if (now - lastCaptchaHintAt < 20_000) return
        lastCaptchaHintAt = now
        toast("出现验证码：按 ← / → 拖动滑块，按 OK 确认")
    }

    private fun showLoginHint() {
        val now = System.currentTimeMillis()
        if (now - lastLoginHintAt < 60_000) return
        lastLoginHintAt = now
        toast("检测到登录弹窗：按菜单键 → 扫码登录")
    }

    private fun showFirstRunHint() {
        if (prefs.getBoolean(PREF_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_HINT_SHOWN, true).apply()
        val chip = TextView(this).apply {
            text = "方向键移动 · OK 确认 · 长按 OK 打开设置"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(16)
                rightMargin = dp(20)
            }
        }
        rootLayout.addView(chip)
        uiHandler.postDelayed({ (chip.parent as? ViewGroup)?.removeView(chip) }, 10_000)
    }

    private fun warnIfWebViewOutdated() {
        if (!WebViewOptimizer.isWebViewOutdated(this)) return
        uiHandler.postDelayed({
            toast("系统 WebView 版本过低，建议更新“Android System WebView”以解决卡顿")
        }, 4000)
    }

    private fun evaluate(script: String) {
        try {
            webView?.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed", t)
        }
    }

    private fun checkLoginStatus() {
        lifecycleScope.launch {
            delay(2500)
            val loggedIn = kotlinx.coroutines.withContext(Dispatchers.IO) {
                CookieHelper.isLoggedIn(DOUYIN_URL)
            }
            if (!loggedIn) showLoginHint()
        }
    }

    private fun nudgeCaptcha(direction: Int) {
        evaluate("window.__tvCaptcha && window.__tvCaptcha.nudge(${direction * 14});")
    }

    // ---------------------------------------------------------------- injected scripts
    // 所有注入脚本都在页面内做幂等保护，避免 onPageFinished / SPA 路由反复挂定时器。

    private fun injectAntiDetection() {
        if (isCompatMode()) return
        val script = """
            (function() {
                try { Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } }); } catch(e) {}
                try { Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } }); } catch(e) {}
                try { Object.defineProperty(navigator, 'languages', { get: function() { return ['zh-CN', 'zh', 'en']; } }); } catch(e) {}
                try { window.chrome = window.chrome || { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} }; } catch(e) {}
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun injectTvCss() {
        val script = """
            (function() {
                if (document.getElementById('tv-optimize-style')) return;
                var style = document.createElement('style');
                style.id = 'tv-optimize-style';
                style.textContent = [
                    '*:focus { outline: none !important; }',
                    'video.tv-pseudo-fullscreen-video {',
                    '  position: fixed !important; inset: 0 !important;',
                    '  width: 100vw !important; height: 100vh !important;',
                    '  max-width: none !important; max-height: none !important;',
                    '  object-fit: contain !important; background: #000 !important;',
                    '  z-index: 2147483646 !important;',
                    '}',
                    'body.tv-video-fullscreen-active { overflow: hidden !important; }',
                    'body.tv-video-fullscreen-active #tv-hl-box,',
                    'body.tv-video-fullscreen-active #tv-hl-label { display: none !important; }',
                    '*, *::before, *::after {',
                    '  animation-duration: 0.01ms !important;',
                    '  transition-duration: 0.05ms !important;',
                    '}'
                ].join('\n');
                document.head.appendChild(style);
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun injectAutoPlay() {
        val script = """
            (function() {
                if (window.__tvAutoPlay) return;
                window.__tvAutoPlay = true;
                var pending = false;
                function hook(video) {
                    if (!video || video.__tvHooked) return;
                    video.__tvHooked = true;
                    try { video.muted = false; if (isFinite(video.volume)) video.volume = 1.0; } catch(e) {}
                    video.setAttribute('playsinline', 'true');
                    video.addEventListener('loadedmetadata', function() {
                        try { video.muted = false; } catch(e) {}
                    });
                    video.addEventListener('playing', function() {
                        try { AndroidInterface.onVideoPlaying(); } catch(e) {}
                    });
                    video.addEventListener('pause', function() {
                        try { AndroidInterface.onVideoPaused(); } catch(e) {}
                    });
                    video.addEventListener('ended', function() {
                        try { AndroidInterface.onVideoEnded(); } catch(e) {}
                        try {
                            if (window.__tvFocus && window.__tvFocus.playNextVideo) {
                                window.__tvFocus.playNextVideo(video);
                            }
                        } catch(e) {}
                    });
                    if (video.preload !== 'metadata') video.preload = 'metadata';
                }
                function scan() {
                    pending = false;
                    if (document.hidden) return;
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) hook(videos[i]);
                }
                function schedule() {
                    if (pending) return;
                    pending = true;
                    setTimeout(scan, 800);
                }
                try {
                    new MutationObserver(schedule).observe(document.documentElement, {
                        childList: true, subtree: true
                    });
                } catch(e) {}
                setInterval(scan, 10000);
                scan();
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun injectCaptchaSupport() {
        val script = """
            (function() {
                if (window.__tvCaptcha) return;
                var btn = null, active = false, startX = 0, startY = 0, curX = 0;

                function findBtn() {
                    return document.querySelector('.secsdk_captcha_drag_button') ||
                           document.querySelector('[class*="captcha_drag"]') ||
                           document.querySelector('[class*="captcha"] [class*="drag"]') ||
                           document.querySelector('[class*="drag-button"]') ||
                           document.querySelector('[class*="slider-button"]') ||
                           document.querySelector('[class*="verify"] [class*="slider"]');
                }

                function fire(target, type, x, y) {
                    try {
                        if (window.Touch && window.TouchEvent) {
                            var t = new Touch({
                                identifier: 1, target: target,
                                clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y
                            });
                            target.dispatchEvent(new TouchEvent(type, {
                                touches: type === 'touchend' ? [] : [t],
                                targetTouches: type === 'touchend' ? [] : [t],
                                changedTouches: [t],
                                bubbles: true, cancelable: true, composed: true
                            }));
                            return;
                        }
                    } catch(e) {}
                    try {
                        var eventType = type === 'touchstart' ? 'mousedown'
                            : (type === 'touchmove' ? 'mousemove' : 'mouseup');
                        var opts = { bubbles: true, cancelable: true, composed: true,
                            clientX: x, clientY: y, view: window, button: 0, buttons: 1 };
                        if (window.PointerEvent) {
                            target.dispatchEvent(new PointerEvent(eventType,
                                Object.assign({ pointerId: 1, pointerType: 'touch', isPrimary: true }, opts)));
                        }
                        target.dispatchEvent(new MouseEvent(eventType, opts));
                    } catch(e) {}
                }

                function begin() {
                    btn = findBtn();
                    if (!btn) return false;
                    var r = btn.getBoundingClientRect();
                    startX = r.left + r.width / 2;
                    startY = r.top + r.height / 2;
                    curX = startX;
                    active = true;
                    fire(btn, 'touchstart', startX, startY);
                    return true;
                }

                window.__tvCaptcha = {
                    nudge: function(dx) {
                        if (!active && !begin()) return 'no-captcha';
                        curX = Math.max(startX, Math.min(window.innerWidth - 24, curX + dx));
                        fire(btn, 'touchmove', curX, startY);
                        return 'moved';
                    },
                    confirm: function() {
                        if (!active) return 'no-drag';
                        fire(btn, 'touchend', curX, startY);
                        active = false;
                        return 'released';
                    }
                };

                setInterval(function() {
                    var b = findBtn();
                    var visible = false;
                    if (b) {
                        var r = b.getBoundingClientRect();
                        visible = r.width > 4 && r.height > 4;
                    }
                    if (visible !== window.__tvCaptchaVisible) {
                        window.__tvCaptchaVisible = visible;
                        try { AndroidInterface.onCaptchaVisible(visible); } catch(e) {}
                    }
                }, 1500);
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun injectLoginWatch() {
        val script = """
            (function() {
                if (window.__tvLoginWatch) return;
                window.__tvLoginWatch = true;
                var warned = false;
                function check() {
                    if (document.hidden) return;
                    var modal = document.querySelector('[class*="login-modal"]') ||
                                document.querySelector('[class*="LoginModal"]') ||
                                document.querySelector('[class*="loginDialog"]') ||
                                document.querySelector('[data-e2e="login-modal"]');
                    var visible = false;
                    if (modal) {
                        var r = modal.getBoundingClientRect();
                        visible = r.height > 20;
                    }
                    if (visible && !warned) {
                        warned = true;
                        try { AndroidInterface.onLoginStatusChanged(false); } catch(e) {}
                    } else if (!visible) {
                        warned = false;
                    }
                    var avatar = document.querySelector('[data-e2e="user-avatar"]') ||
                                 document.querySelector('[class*="user-avatar"]');
                    var loggedIn = !!(avatar && avatar.getBoundingClientRect().height > 8);
                    if (loggedIn !== window.__tvLoggedIn) {
                        window.__tvLoggedIn = loggedIn;
                        if (loggedIn) {
                            try { AndroidInterface.onLoginStatusChanged(true); } catch(e) {}
                        }
                    }
                }
                setInterval(check, 4000);
            })();
        """.trimIndent()
        evaluate(script)
    }

    // ---------------------------------------------------------------- focus engine

    private fun scheduleFocusEngine() {
        if (!webViewReady) return
        initFocusEngine()
    }

    private fun initFocusEngine() {
        val script = """
            (function() {
                if (window.__tvFocus && window.__tvFocus.cleanup) {
                    try { window.__tvFocus.cleanup(); } catch(e) {}
                }
                var TV = window.__tvFocus = {};
                TV.__ready = true;

                var oldHl = document.getElementById('tv-hl-box');
                if (oldHl) oldHl.remove();
                var hl = document.createElement('div');
                hl.id = 'tv-hl-box';
                hl.style.cssText = [
                    'position:fixed', 'top:0', 'left:0', 'width:0', 'height:0',
                    'border:4px solid #FE2C55', 'border-radius:8px',
                    'box-shadow:0 0 16px rgba(254,44,85,1)',
                    'pointer-events:none', 'z-index:2147483647',
                    'transition:transform 0.15s ease-out, width 0.15s, height 0.15s',
                    'display:none', 'background:transparent', 'box-sizing:border-box'
                ].join(';');
                document.body.appendChild(hl);
                TV.hl = hl;

                var oldLabel = document.getElementById('tv-hl-label');
                if (oldLabel) oldLabel.remove();
                var label = document.createElement('div');
                label.id = 'tv-hl-label';
                label.style.cssText = [
                    'position:fixed', 'top:8px', 'left:50%', 'transform:translateX(-50%)',
                    'background:rgba(254,44,85,0.95)', 'color:#fff', 'padding:5px 14px',
                    'border-radius:14px', 'font-size:13px', 'z-index:2147483647',
                    'pointer-events:none', 'display:none', 'font-family:sans-serif',
                    'max-width:70vw', 'white-space:nowrap', 'overflow:hidden',
                    'text-overflow:ellipsis', 'font-weight:bold'
                ].join(';');
                document.body.appendChild(label);
                TV.label = label;

                function showLabel(text) {
                    label.textContent = text;
                    label.style.display = 'block';
                    clearTimeout(TV.labelTimer);
                    TV.labelTimer = setTimeout(function() { label.style.display = 'none'; }, 1500);
                }

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

                function makeItem(el, type, name, actionEl, videoEl) {
                    var r = el.getBoundingClientRect();
                    return {
                        el: el, actionEl: actionEl || el, videoEl: videoEl || null, type: type,
                        name: (name || '').toString().trim().substring(0, 30),
                        x: r.left + r.width / 2, y: r.top + r.height / 2,
                        l: r.left, t: r.top, r: r.right, b: r.bottom,
                        w: r.width, h: r.height
                    };
                }

                function centerOf(el) {
                    var r = el.getBoundingClientRect();
                    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
                }

                function fireRemoteClick(el) {
                    if (!el) return false;
                    var c = centerOf(el);
                    var opts = { bubbles: true, cancelable: true, composed: true, view: window,
                        clientX: c.x, clientY: c.y };
                    try { el.focus && el.focus({ preventScroll: true }); } catch(e) {}
                    // 只派发按下/抬起序列，click 由 el.click() 统一触发，避免一次操作被处理两次
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(type) {
                        try {
                            var ev = type.indexOf('pointer') === 0 && window.PointerEvent
                                ? new PointerEvent(type, Object.assign({ pointerId: 1, pointerType: 'mouse', isPrimary: true }, opts))
                                : new MouseEvent(type, opts);
                            el.dispatchEvent(ev);
                        } catch(e) {}
                    });
                    try { el.click(); } catch(e) {}
                    return true;
                }

                function getActionTarget(el) {
                    if (!el) return null;
                    return el.closest('a[href],button,[role="button"],[role="tab"],[tabindex]') ||
                           el.querySelector('a[href],button,[role="button"],[role="tab"],[tabindex]') ||
                           el;
                }

                function getPrimaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }

                function findVideoForElement(el) {
                    if (!el) return null;
                    var direct = el.querySelector && el.querySelector('video');
                    if (direct) return direct;
                    var card = el.closest('[data-aweme-id], article, [class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]');
                    if (card) {
                        var nested = card.querySelector && card.querySelector('video');
                        if (nested) return nested;
                    }
                    var targetRect = el.getBoundingClientRect();
                    var minOverlap = Math.max(1600, targetRect.width * targetRect.height * 0.18);
                    var best = null, bestOverlap = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var ow = Math.max(0, Math.min(r.right, targetRect.right) - Math.max(r.left, targetRect.left));
                        var oh = Math.max(0, Math.min(r.bottom, targetRect.bottom) - Math.max(r.top, targetRect.top));
                        var overlap = ow * oh;
                        if (overlap > bestOverlap && overlap >= minOverlap) {
                            bestOverlap = overlap;
                            best = v;
                        }
                    });
                    return best;
                }

                function enterPseudoFullscreen(video) {
                    document.querySelectorAll('video.tv-pseudo-fullscreen-video').forEach(function(v) {
                        if (v !== video) v.classList.remove('tv-pseudo-fullscreen-video');
                    });
                    video.classList.add('tv-pseudo-fullscreen-video');
                    document.body.classList.add('tv-video-fullscreen-active');
                    try { AndroidInterface.onVideoFullscreenChanged(true); } catch(e) {}
                }

                function pauseOtherVideos(activeVideo) {
                    document.querySelectorAll('video').forEach(function(v) {
                        if (v !== activeVideo) {
                            try { v.pause(); } catch(e) {}
                        }
                    });
                }

                function playFullscreenVideo(video) {
                    if (!video) return false;
                    pauseOtherVideos(video);
                    try { video.muted = false; video.volume = 1; } catch(e) {}
                    try { video.preload = 'auto'; } catch(e) {}
                    try {
                        var p = video.play();
                        if (p && p.catch) p.catch(function() {});
                    } catch(e) {}
                    try {
                        if (video.requestFullscreen) video.requestFullscreen();
                        else if (video.webkitRequestFullscreen) video.webkitRequestFullscreen();
                    } catch(e) {}
                    enterPseudoFullscreen(video);
                    return true;
                }

                function openItem(item) {
                    var target = getActionTarget(item.actionEl || item.el);
                    var beforeHref = location.href;
                    var targetHref = target && target.getAttribute ? (target.getAttribute('href') || '') : '';
                    fireRemoteClick(target);
                    setTimeout(function() {
                        if (targetHref && location.href === beforeHref) {
                            try { location.href = targetHref; } catch(e) {}
                        }
                    }, 120);
                    setTimeout(function() {
                        var changed = location.href !== beforeHref;
                        var videoPage = location.pathname.indexOf('/video/') > -1 ||
                                        location.pathname.indexOf('/note/') > -1;
                        if (!changed && !videoPage) return;
                        var v = getPrimaryVideo();
                        if (v) playFullscreenVideo(v);
                    }, 1200);
                }

                function playNextVideo(currentVideo) {
                    gather();
                    var currentIndex = -1;
                    if (TV.current) currentIndex = TV.items.indexOf(TV.current);
                    if (currentIndex < 0 && currentVideo) {
                        for (var i = 0; i < TV.items.length; i++) {
                            if (TV.items[i].videoEl === currentVideo ||
                                findVideoForElement(TV.items[i].el) === currentVideo) {
                                currentIndex = i;
                                break;
                            }
                        }
                    }
                    var next = null;
                    for (var j = Math.max(0, currentIndex + 1); j < TV.items.length; j++) {
                        if (TV.items[j].type === 'video') { next = TV.items[j]; break; }
                    }
                    if (!next) {
                        window.scrollBy({ top: window.innerHeight * 0.85, behavior: 'smooth' });
                        setTimeout(function() {
                            gather();
                            var videos = TV.items.filter(function(i) { return i.type === 'video'; });
                            if (videos.length > 0) {
                                TV.current = videos[0];
                                drawBox();
                                var v = TV.current.videoEl || findVideoForElement(TV.current.el);
                                if (v) playFullscreenVideo(v); else openItem(TV.current);
                            }
                        }, 700);
                        return 'scroll-next';
                    }
                    TV.current = next;
                    try { next.el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch(e) {}
                    setTimeout(function() {
                        drawBox();
                        var v = next.videoEl || findVideoForElement(next.el);
                        if (v) playFullscreenVideo(v); else openItem(next);
                    }, 150);
                    return 'next';
                }
                TV.playNextVideo = playNextVideo;

                function gather() {
                    var items = [];
                    var seen = new Set();
                    function add(el, type, name, actionEl, videoEl) {
                        if (!el || seen.has(el) || !isVisible(el)) return;
                        seen.add(el);
                        items.push(makeItem(el, type, name, actionEl, videoEl));
                    }
                    document.querySelectorAll('[data-aweme-id]').forEach(function(el) {
                        var link = el.querySelector('a');
                        var video = el.querySelector('video');
                        var titleEl = el.querySelector('.z72L2AHI') ||
                                      el.querySelector('[class*="title"]') ||
                                      el.querySelector('img[alt]') ||
                                      el.querySelector('[class*="desc"]');
                        var name = titleEl ?
                            (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
                        add(el, 'video', name, link || el, video);
                    });
                    document.querySelectorAll('[data-e2e*="feed"], [class*="feed"] a[href], [class*="video"] a[href], [class*="card"] a[href]').forEach(function(link) {
                        var href = link.getAttribute('href') || '';
                        var text = (link.textContent || link.getAttribute('aria-label') || '').trim();
                        if (href.indexOf('/video/') < 0 && href.indexOf('/note/') < 0 && text.length === 0) return;
                        var titleEl = link.querySelector('img[alt]') || link.querySelector('[class*="title"]') || link.querySelector('[class*="desc"]');
                        var name = titleEl ? (titleEl.textContent || titleEl.getAttribute('alt') || text || '视频').trim() : (text || '视频');
                        var card = link.closest('[data-aweme-id], article, [class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]') || link;
                        var video = card.querySelector && card.querySelector('video');
                        add(card, 'video', name, link, video);
                    });
                    if (items.length === 0) {
                        document.querySelectorAll('a[href*="/video/"], a[href*="/note/"]').forEach(function(link) {
                            if (!isVisible(link)) return;
                            var r = link.getBoundingClientRect();
                            if (r.width < 10 || r.height < 10) return;
                            if (seen.has(link)) return;
                            seen.add(link);
                            var container = link.closest('[data-aweme-id]') ||
                                            link.closest('article') ||
                                            link.closest('[class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]') ||
                                            link;
                            var titleEl = link.querySelector('img[alt]') || link.querySelector('[class*="title"]') || link.querySelector('[class*="desc"]');
                            var name = titleEl ? (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
                            items.push(makeItem(container, 'video', name, link, null));
                        });
                    }
                    document.querySelectorAll('[role="tab"]').forEach(function(el) {
                        var name = (el.textContent || '').trim();
                        if (name) add(el, 'toptab', name);
                    });
                    document.querySelectorAll('[aria-selected], [data-e2e*="tab"], nav a, header a').forEach(function(el) {
                        var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
                        if (name && name.length < 20) add(el, 'toptab', name);
                    });
                    if (items.filter(function(i) { return i.type === 'toptab'; }).length === 0) {
                        document.querySelectorAll('.semi-tabs-tab, .web-nav-wrapper a, .NavBar-Container a, [class*="nav"] a, [class*="Nav"] a').forEach(function(el) {
                            var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
                            if (name && name.length < 20) add(el, 'toptab', name);
                        });
                    }
                    document.querySelectorAll('.tab-discover a').forEach(function(link) {
                        var nameEl = link.querySelector('.Ou5DxC0w') || link.querySelector('span');
                        var name = nameEl ? nameEl.textContent.trim() : (link.textContent.trim() || '菜单');
                        add(link, 'leftnav', name);
                    });
                    if (items.filter(function(i) { return i.type === 'leftnav'; }).length === 0) {
                        document.querySelectorAll('.sidebar-container a, [class*="Sidebar"] a, [class*="side-nav"] a, aside a, [class*="menu"] a, [class*="Menu"] a').forEach(function(el) {
                            var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
                            if (name && name.length < 20) add(el, 'leftnav', name);
                        });
                    }
                    TV.items = items;
                    return items.length;
                }
                TV.refresh = gather;

                function gatherLight() {
                    if (!TV.items) return;
                    for (var i = 0; i < TV.items.length; i++) {
                        var it = TV.items[i];
                        if (!it.el || !document.body.contains(it.el)) continue;
                        var r = it.el.getBoundingClientRect();
                        if (r.width === 0 || r.height === 0) continue;
                        it.x = r.left + r.width / 2;
                        it.y = r.top + r.height / 2;
                        it.l = r.left; it.t = r.top; it.r = r.right; it.b = r.bottom;
                        it.w = r.width; it.h = r.height;
                    }
                }
                TV.gatherLight = gatherLight;

                function drawBox() {
                    if (!TV.current) { hl.style.display = 'none'; return; }
                    var el = TV.current.el;
                    if (!el || !document.body.contains(el)) { hl.style.display = 'none'; return; }
                    var r = el.getBoundingClientRect();
                    if (r.width < 5 || r.height < 5) { hl.style.display = 'none'; return; }
                    TV.current.x = r.left + r.width / 2;
                    TV.current.y = r.top + r.height / 2;
                    hl.style.display = 'block';
                    hl.style.width = (r.width + 8) + 'px';
                    hl.style.height = (r.height + 8) + 'px';
                    hl.style.transform = 'translate(' + (r.left - 4) + 'px,' + (r.top - 4) + 'px)';
                    var typeLabel = { leftnav: '菜单', toptab: '分类', video: '视频' };
                    showLabel('▶ ' + (typeLabel[TV.current.type] || '') + ': ' + TV.current.name);
                }
                TV.draw = drawBox;

                // 空间光标：页面结构变化导致选不到卡片时，用坐标游标 + elementFromPoint 兜底点击
                function ensureCursor() {
                    if (!TV.cursor) {
                        TV.cursor = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
                    }
                    return TV.cursor;
                }

                function drawCursor() {
                    var c = ensureCursor();
                    hl.style.display = 'block';
                    hl.style.width = '40px';
                    hl.style.height = '40px';
                    hl.style.transform = 'translate(' + (c.x - 20) + 'px,' + (c.y - 20) + 'px)';
                    var el = document.elementFromPoint(c.x, c.y);
                    var name = '';
                    if (el) {
                        name = (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '')
                            .trim().substring(0, 24);
                    }
                    showLabel('光标' + (name ? '：' + name : ''));
                }

                function moveCursor(dx, dy) {
                    var c = ensureCursor();
                    c.x = Math.max(12, Math.min(window.innerWidth - 12, c.x + dx * window.innerWidth * 0.16));
                    c.y = Math.max(12, Math.min(window.innerHeight - 12, c.y + dy * window.innerHeight * 0.16));
                    drawCursor();
                    return true;
                }

                function clickCursor() {
                    var c = ensureCursor();
                    var el = document.elementFromPoint(c.x, c.y);
                    if (!el) return false;
                    if (el.tagName === 'VIDEO') return playFullscreenVideo(el);
                    fireRemoteClick(getActionTarget(el) || el);
                    return true;
                }

                function findGridNeighbor(cur, dx, dy) {
                    if (!TV.items || TV.items.length === 0 || !cur || !cur.el) return null;
                    var curR = cur.el.getBoundingClientRect();
                    var cx = curR.left + curR.width / 2;
                    var cy = curR.top + curR.height / 2;
                    var curArea = Math.max(1, curR.width * curR.height);
                    var best = null, bestScore = Infinity;
                    for (var i = 0; i < TV.items.length; i++) {
                        var it = TV.items[i];
                        if (it.el === cur.el || !document.body.contains(it.el)) continue;
                        var r = it.el.getBoundingClientRect();
                        if (r.width < 10 || r.height < 10) continue;
                        var ix = r.left + r.width / 2;
                        var iy = r.top + r.height / 2;
                        var primary = 0, secondary = 0, overlap = 0, overlapRatio = 0, gap = 0;
                        if (dx > 0) {
                            gap = r.left - curR.right;
                            if (gap < -Math.min(80, curR.width * 0.18) && ix <= cx) continue;
                            primary = Math.max(0, gap);
                            secondary = Math.abs(iy - cy);
                            overlap = Math.max(0, Math.min(r.bottom, curR.bottom) - Math.max(r.top, curR.top));
                            overlapRatio = overlap / Math.max(1, Math.min(r.height, curR.height));
                        } else if (dx < 0) {
                            gap = curR.left - r.right;
                            if (gap < -Math.min(80, curR.width * 0.18) && ix >= cx) continue;
                            primary = Math.max(0, gap);
                            secondary = Math.abs(iy - cy);
                            overlap = Math.max(0, Math.min(r.bottom, curR.bottom) - Math.max(r.top, curR.top));
                            overlapRatio = overlap / Math.max(1, Math.min(r.height, curR.height));
                        } else if (dy > 0) {
                            gap = r.top - curR.bottom;
                            if (gap < -Math.min(80, curR.height * 0.22) && iy <= cy) continue;
                            primary = Math.max(0, gap);
                            secondary = Math.abs(ix - cx);
                            overlap = Math.max(0, Math.min(r.right, curR.right) - Math.max(r.left, curR.left));
                            overlapRatio = overlap / Math.max(1, Math.min(r.width, curR.width));
                        } else if (dy < 0) {
                            gap = curR.top - r.bottom;
                            if (gap < -Math.min(80, curR.height * 0.22) && iy >= cy) continue;
                            primary = Math.max(0, gap);
                            secondary = Math.abs(ix - cx);
                            overlap = Math.max(0, Math.min(r.right, curR.right) - Math.max(r.left, curR.left));
                            overlapRatio = overlap / Math.max(1, Math.min(r.width, curR.width));
                        }
                        var sizePenalty = Math.abs((r.width * r.height) - curArea) / curArea * 10;
                        var typeBonus = it.type === cur.type ? -30 : 20;
                        var score = primary * 2 + secondary * (overlapRatio > 0.18 ? 0.55 : 1.25) - overlapRatio * 180 + sizePenalty + typeBonus;
                        if (score < bestScore) { bestScore = score; best = it; }
                    }
                    return best;
                }

                function findNearestInDirection(cur, dx, dy) {
                    if (!TV.items || TV.items.length === 0) return null;
                    var curR = cur.el.getBoundingClientRect();
                    var cx = curR.left + curR.width / 2;
                    var cy = curR.top + curR.height / 2;
                    var best = null, bestScore = Infinity;
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
                        if (dy > 0) { if (ddy < curR.height * 0.15) continue; }
                        else if (dy < 0) { if (ddy > -curR.height * 0.15) continue; }
                        else if (dx > 0) { if (ddx < curR.width * 0.15) continue; }
                        else if (dx < 0) { if (ddx > -curR.width * 0.15) continue; }
                        var primary, secondary;
                        if (dy !== 0) { primary = Math.abs(ddy); secondary = Math.abs(ddx); }
                        else { primary = Math.abs(ddx); secondary = Math.abs(ddy); }
                        if (dy !== 0 && secondary > window.innerWidth * 0.6) continue;
                        if (dx !== 0 && secondary > window.innerHeight * 0.6) continue;
                        var score = primary + secondary * 1.5;
                        if (score < bestScore) { bestScore = score; best = it; }
                    }
                    return best;
                }

                function moveFocus(dx, dy) {
                    gatherLight();
                    if (!TV.items || TV.items.length === 0) {
                        return moveCursor(dx, dy);
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
                            try { best.el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch(e) {}
                            setTimeout(drawBox, 60);
                        }
                        return true;
                    }
                    var next = findGridNeighbor(cur, dx, dy) || findNearestInDirection(cur, dx, dy);
                    if (!next) {
                        if (dy > 0 && cur.type === 'video') {
                            window.scrollBy({ top: window.innerHeight * 0.7, behavior: 'smooth' });
                            setTimeout(function() {
                                gather();
                                gatherLight();
                                var nxt = findNearestInDirection(TV.current, 0, 1);
                                if (nxt) {
                                    TV.current = nxt;
                                    try { nxt.el.scrollIntoView({ block: 'center' }); } catch(e) {}
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
                    try { next.el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch(e) {}
                    setTimeout(drawBox, 60);
                    return true;
                }
                TV.move = moveFocus;

                function activate() {
                    if (!TV.items || TV.items.length === 0) {
                        return clickCursor() ? 'cursor-click' : 'cursor-miss';
                    }
                    if (!TV.current) { moveFocus(0, 0); return 'init'; }
                    var item = TV.current;
                    var el = item.el;
                    if (!document.body.contains(el)) {
                        TV.current = null;
                        moveFocus(0, 0);
                        return 'reset';
                    }
                    if (item.type === 'video') {
                        var selectedVideo = item.videoEl || findVideoForElement(el);
                        if (playFullscreenVideo(selectedVideo)) {
                            showLabel('▶ 全屏播放: ' + item.name);
                            return 'video-fullscreen';
                        }
                        openItem(item);
                        showLabel('▶ 打开: ' + item.name);
                        setTimeout(function() { TV.current = null; }, 1500);
                        return 'video-clicked';
                    }
                    var inner = getActionTarget(el);
                    var beforeHref = location.href;
                    fireRemoteClick(inner);
                    setTimeout(function() {
                        var href = inner && inner.getAttribute ? inner.getAttribute('href') : '';
                        if (href && location.href === beforeHref) {
                            try { location.href = href; } catch(e) {}
                        }
                    }, 120);
                    showLabel('✓ ' + item.name);
                    setTimeout(function() {
                        gather();
                        gatherLight();
                        if (item.type === 'leftnav' || item.type === 'toptab') TV.current = null;
                    }, 500);
                    return 'clicked';
                }
                TV.activate = activate;

                TV.primaryVideo = getPrimaryVideo;
                TV.playFullscreenVideo = playFullscreenVideo;
                TV.playPrimary = function() {
                    var v = getPrimaryVideo();
                    if (!v) return 'no-video';
                    playFullscreenVideo(v);
                    return 'ok';
                };

                var refreshTimer;
                var observer = new MutationObserver(function() {
                    clearTimeout(refreshTimer);
                    refreshTimer = setTimeout(function() {
                        if (document.hidden) return;
                        gather();
                        if (TV.current && document.body.contains(TV.current.el)) drawBox();
                        else if (!TV.items || TV.items.length === 0) drawCursor();
                    }, 1200);
                });
                observer.observe(document.body, { childList: true, subtree: true });
                TV.observer = observer;

                var rafPending = false;
                var scrollHandler = function() {
                    if (rafPending) return;
                    rafPending = true;
                    requestAnimationFrame(function() {
                        rafPending = false;
                        if (TV.current) drawBox();
                        else if (TV.cursor) drawCursor();
                    });
                };
                window.addEventListener('scroll', scrollHandler, { passive: true, capture: true });
                document.addEventListener('scroll', scrollHandler, { passive: true, capture: true });
                TV.scrollHandler = scrollHandler;

                TV.refreshInterval = setInterval(function() {
                    if (!document.hidden) gather();
                }, 12000);

                var resizeHandler = function() {
                    gather();
                    if (TV.current) drawBox();
                    else if (TV.cursor) drawCursor();
                };
                window.addEventListener('resize', resizeHandler);
                TV.resizeHandler = resizeHandler;

                TV.cleanup = function() {
                    try { observer.disconnect(); } catch(e) {}
                    try { window.removeEventListener('scroll', scrollHandler); } catch(e) {}
                    try { document.removeEventListener('scroll', scrollHandler); } catch(e) {}
                    try { window.removeEventListener('resize', resizeHandler); } catch(e) {}
                    try { clearInterval(TV.refreshInterval); } catch(e) {}
                    try { hl.remove(); label.remove(); } catch(e) {}
                };

                gather();
                setTimeout(function() {
                    if (TV.items && TV.items.length > 0) moveFocus(0, 0);
                }, 300);

                return 'ok:' + TV.items.length;
            })();
        """.trimIndent()

        webView?.evaluateJavascript(script) { result ->
            Log.d(TAG, "Focus engine init: $result")
        }
    }

    private fun focusEngineNavigate(dx: Int, dy: Int) {
        val script = """
            (function() {
                var TV = window.__tvFocus;
                var fullscreenVideo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (fullscreenVideo) {
                    if ($dx !== 0) {
                        try {
                            var duration = fullscreenVideo.duration || (fullscreenVideo.currentTime + 15);
                            fullscreenVideo.currentTime = Math.max(0, Math.min(duration, fullscreenVideo.currentTime + ($dx > 0 ? 10 : -10)));
                            return 'seek';
                        } catch(e) {}
                    }
                    if ($dy > 0 && TV && TV.playNextVideo) return TV.playNextVideo(fullscreenVideo);
                    if ($dy < 0) {
                        window.scrollBy({ top: -window.innerHeight * 0.85, behavior: 'smooth' });
                        return 'prev';
                    }
                }
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                var path = location.pathname;
                var inVideoPage = path.indexOf('/video/') > -1 || path.indexOf('/note/') > -1;
                if (inVideoPage) {
                    var v = primaryVideo();
                    if (v && $dx !== 0) {
                        try {
                            var d = v.duration || (v.currentTime + 15);
                            v.currentTime = Math.max(0, Math.min(d, v.currentTime + ($dx > 0 ? 10 : -10)));
                            return 'seek';
                        } catch(e) {}
                    }
                    if ($dy !== 0) {
                        window.scrollBy({ top: ($dy > 0 ? 1 : -1) * window.innerHeight * 0.9, behavior: 'smooth' });
                        return 'switch-video';
                    }
                }
                if (TV) {
                    TV.move($dx, $dy);
                    return 'focus';
                }
                window.scrollBy(0, $dy * window.innerHeight * 0.6);
                return 'scroll';
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun focusEngineAction() {
        val script = """
            (function() {
                var TV = window.__tvFocus;
                var fullscreenVideo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (fullscreenVideo) {
                    try {
                        if (fullscreenVideo.paused) { fullscreenVideo.play(); return 'play'; }
                        fullscreenVideo.pause();
                        return 'pause';
                    } catch(e) {}
                }
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                var path = location.pathname;
                var inVideoPage = path.indexOf('/video/') > -1 || path.indexOf('/note/') > -1;
                if (inVideoPage && primaryVideo() && TV) {
                    return TV.playPrimary();
                }
                if (TV) return TV.activate();
                return 'no-engine';
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            Log.d(TAG, "Focus action: $result")
        }
    }

    private fun exitPseudoFullscreen() {
        val script = """
            (function() {
                var pseudo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (pseudo) {
                    pseudo.classList.remove('tv-pseudo-fullscreen-video');
                    document.body.classList.remove('tv-video-fullscreen-active');
                    try { AndroidInterface.onVideoFullscreenChanged(false); } catch(e) {}
                    return 'pseudo-exited';
                }
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                    return 'exited';
                }
                return 'no-fullscreen';
            })();
        """.trimIndent()
        evaluate(script)
        isPseudoVideoFullscreen = false
    }

    private fun toggleVideoPlayPause() {
        val script = """
            (function() {
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                var v = primaryVideo() || document.querySelector('video');
                if (v) { if (v.paused) v.play(); else v.pause(); }
            })();
        """.trimIndent()
        evaluate(script)
    }

    private fun playVideo() {
        evaluate("(function(){var v=document.querySelector('video');if(v&&v.paused)v.play();})();")
    }

    private fun pauseVideo() {
        evaluate("(function(){var v=document.querySelector('video');if(v&&!v.paused)v.pause();})();")
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        try {
            webView?.onResume()
            webView?.resumeTimers()
            webView?.requestFocus()
        } catch (t: Throwable) {
            Log.w(TAG, "onResume failed", t)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView?.onPause()
            webView?.pauseTimers()
        } catch (t: Throwable) {
            Log.w(TAG, "onPause failed", t)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            CookieHelper.saveCookies(this@MainActivity, DOUYIN_URL)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
        webView?.requestFocus()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 只在真正吃紧时释放内存缓存，避免周期性清缓存导致视频反复重新下载
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            try {
                webView?.clearCache(false)
            } catch (t: Throwable) {
                Log.w(TAG, "trim memory failed", t)
            }
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        destroyWebView()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            webView?.saveState(outState)
        } catch (t: Throwable) {
            Log.w(TAG, "saveState failed", t)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try {
            webView?.restoreState(savedInstanceState)
        } catch (t: Throwable) {
            Log.w(TAG, "restoreState failed", t)
        }
    }
}
