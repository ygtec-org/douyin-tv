package com.douyin.tv.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewOptimizer {
    
    private const val TAG = "WebViewOptimizer"
    
    enum class DeviceLevel {
        HIGH,
        MEDIUM,
        LOW
    }
    
    private var cachedDeviceLevel: DeviceLevel? = null
    
    fun getDeviceLevel(context: Context): DeviceLevel {
        cachedDeviceLevel?.let { return it }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemMB = memoryInfo.totalMem / (1024 * 1024)
        val level = when {
            totalMemMB >= 3072 -> DeviceLevel.HIGH
            totalMemMB >= 1536 -> DeviceLevel.MEDIUM
            else -> DeviceLevel.LOW
        }
        cachedDeviceLevel = level
        return level
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    fun optimizeForTV(webView: WebView, context: Context) {
        val deviceLevel = getDeviceLevel(context)
        Log.d(TAG, "Device level: $deviceLevel")
        
        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            databaseEnabled = true
            
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            blockNetworkImage = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            textZoom = 100
            minimumFontSize = 12
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            if (deviceLevel == DeviceLevel.LOW) {
                saveFormData = false
            }
        }
        
        when (deviceLevel) {
            DeviceLevel.HIGH -> webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            DeviceLevel.MEDIUM -> webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            DeviceLevel.LOW -> webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        }
    }
    
    fun clearCache(webView: WebView) {
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
    }
}
