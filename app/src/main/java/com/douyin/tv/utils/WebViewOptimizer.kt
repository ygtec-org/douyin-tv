package com.douyin.tv.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewOptimizer {
    
    private const val TAG = "WebViewOptimizer"
    
    /**
     * 设备性能等级
     */
    enum class DeviceLevel {
        HIGH,    // 高端设备: >= 3GB RAM
        MEDIUM,  // 中端设备: 1.5-3GB RAM
        LOW      // 低端设备: < 1.5GB RAM
    }
    
    /**
     * 检测设备性能等级
     */
    fun getDeviceLevel(): DeviceLevel {
        val activityManager = android.app.ActivityManager(
            android.app.ActivityThread.currentApplication()
                .getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        )
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemMB = memoryInfo.totalMem / (1024 * 1024)
        
        return when {
            totalMemMB >= 3072 -> DeviceLevel.HIGH
            totalMemMB >= 1536 -> DeviceLevel.MEDIUM
            else -> DeviceLevel.LOW
        }
    }
    
    /**
     * 获取最大可用内存(MB)
     */
    fun getMaxMemoryMB(): Int {
        return (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
    }
    
    /**
     * 优化WebView配置以获得最佳TV体验
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun optimizeForTV(webView: WebView) {
        val deviceLevel = getDeviceLevel()
        Log.d(TAG, "Device level: $deviceLevel, Max memory: ${getMaxMemoryMB()}MB")
        
        webView.settings.apply {
            // JavaScript支持
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            
            // 存储支持
            domStorageEnabled = true
            databaseEnabled = true
            
            // 缓存配置 - 低配设备使用缓存优先
            cacheMode = when (deviceLevel) {
                DeviceLevel.HIGH -> WebSettings.LOAD_DEFAULT
                DeviceLevel.MEDIUM -> WebSettings.LOAD_DEFAULT
                DeviceLevel.LOW -> WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            
            // 缓存大小优化
            val cacheSize = when (deviceLevel) {
                DeviceLevel.HIGH -> 1024 * 1024 * 50L  // 50MB
                DeviceLevel.MEDIUM -> 1024 * 1024 * 20L // 20MB
                DeviceLevel.LOW -> 1024 * 1024 * 10L   // 10MB
            }
            try {
                javaClass.getMethod("setAppCacheMaxSize", Long::class.java)
                    .invoke(this, cacheSize)
            } catch (e: Exception) {
                Log.d(TAG, "setAppCacheMaxSize not available")
            }
            
            // 网络配置
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            blockNetworkImage = false
            loadsImagesAutomatically = true
            
            // 视口配置
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // 低配设备使用简单布局算法
            layoutAlgorithm = when (deviceLevel) {
                DeviceLevel.HIGH -> WebSettings.LayoutAlgorithm.NORMAL
                DeviceLevel.MEDIUM -> WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                DeviceLevel.LOW -> WebSettings.LayoutAlgorithm.NORMAL
            }
            
            // 缩放配置 - 禁用缩放避免误触
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            
            // 媒体配置
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            
            // 字体配置
            textZoom = 100
            minimumFontSize = 12
            
            // User Agent - 使用PC版获得更好体验
            userAgentString = buildUserAgent(userAgentString)
            
            // 低配设备额外优化
            if (deviceLevel == DeviceLevel.LOW) {
                // 禁用图片下载(仅对低端设备)
                loadsImagesAutomatically = true
                blockNetworkImage = false
                
                // 禁用表单数据
                saveFormData = false
                
                // 限制渲染优先级
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }
        }
        
        // 启用应用缓存
        try {
            val appCachePath = webView.context.getCacheDir().absolutePath
            webView.settings.javaClass.getMethod("setAppCachePath", String::class.java)
                .invoke(webView.settings, appCachePath)
            webView.settings.javaClass.getMethod("setAppCacheEnabled", Boolean::class.java)
                .invoke(webView.settings, true)
        } catch (e: Exception) {
            Log.d(TAG, "App cache not available")
        }
        
        // 根据设备性能设置渲染层类型
        when (deviceLevel) {
            DeviceLevel.HIGH -> {
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            }
            DeviceLevel.MEDIUM -> {
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            }
            DeviceLevel.LOW -> {
                // 低内存设备使用软件渲染避免OOM
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            }
        }
    }
    
    /**
     * 构建适合电视的User Agent
     */
    private fun buildUserAgent(originalUA: String): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    /**
     * 清除WebView缓存
     */
    fun clearCache(webView: WebView) {
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
    }
    
    /**
     * 获取Cookie管理器(单例)
     */
    fun getCookieManager(): CookieManager {
        return CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(
                android.webkit.WebView(android.app.ActivityThread.currentApplication()),
                true
            )
        }
    }
}
