package com.douyin.tv.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat

object WebViewOptimizer {

    private const val TAG = "WebViewOptimizer"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    enum class DeviceLevel { HIGH, MEDIUM, LOW }

    @Volatile
    private var cachedDeviceLevel: DeviceLevel? = null

    fun getDeviceLevel(context: Context): DeviceLevel {
        cachedDeviceLevel?.let { return it }
        val level = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            when {
                info.totalMem >= 3L * 1024 * 1024 * 1024 -> DeviceLevel.HIGH
                info.totalMem >= 1536L * 1024 * 1024 -> DeviceLevel.MEDIUM
                else -> DeviceLevel.LOW
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getDeviceLevel failed", t)
            DeviceLevel.MEDIUM
        }
        cachedDeviceLevel = level
        return level
    }

    /**
     * 系统 WebView 主版本号。老电视常见 Chrome/62（Android 8），
     * 无法完整运行 douyin.com 的现代前端，这是“卡顿/白屏/按键无响应”的根因之一。
     */
    fun getWebViewMajorVersion(context: Context): Int {
        return try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context) ?: return 0
            pkg.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            Log.w(TAG, "getWebViewMajorVersion failed", t)
            0
        }
    }

    fun isWebViewOutdated(context: Context): Boolean {
        val major = getWebViewMajorVersion(context)
        return major in 1..79
    }

    /** 兼容模式使用手机 UA，老内核可以少掉大量桌面端脚本与高码率播放器逻辑。 */
    private fun mobileUa(major: Int): String {
        val version = if (major >= 80) "$major.0.0.0" else "62.0.3202.84"
        return "Mozilla/5.0 (Linux; Android 10; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
    }

    fun resolveUserAgent(context: Context, compatMode: Boolean): String {
        val major = getWebViewMajorVersion(context)
        return if (compatMode || (major in 1..79)) mobileUa(major) else DESKTOP_UA
    }

    fun applyUserAgent(webView: WebView, context: Context, compatMode: Boolean) {
        webView.settings.userAgentString = resolveUserAgent(context, compatMode)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun optimizeForTV(webView: WebView, context: Context, compatMode: Boolean) {
        val deviceLevel = getDeviceLevel(context)
        Log.d(TAG, "Device level: $deviceLevel, WebView: ${getWebViewMajorVersion(context)}, compat: $compatMode")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 页面自行弹窗交给站内处理，避免未知窗口把 WebView 带到一个无法返回的页面
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            cacheMode = WebSettings.LOAD_DEFAULT
            // ALWAYS_ALLOW 会放行混合内容脚本，兼容性模式已足够
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            blockNetworkImage = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            minimumFontSize = 10

            userAgentString = resolveUserAgent(context, compatMode)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 离屏预栅格化，滑动/翻视频更平滑
                offscreenPreRaster = true
            }
        }

        // 之前对低配设备用软件层，视频解码与页面滚动会严重掉帧，这里统一走硬件层
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "static WebView tuning failed", t)
        }
    }

    fun clearCache(webView: WebView) {
        try {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
        } catch (t: Throwable) {
            Log.w(TAG, "clearCache failed", t)
        }
    }
}
