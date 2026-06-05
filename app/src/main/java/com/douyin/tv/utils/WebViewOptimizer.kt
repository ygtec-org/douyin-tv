package com.douyin.tv.utils

import android.webkit.WebSettings
import android.webkit.WebView

object WebViewOptimizer {
    
    /**
     * 优化WebView配置以获得最佳TV体验
     */
    fun optimizeForTV(webView: WebView) {
        webView.settings.apply {
            // JavaScript支持
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            
            // 存储支持
            domStorageEnabled = true
            databaseEnabled = true
            
            // 缓存配置 - 使用缓存优先提升加载速度
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // 网络配置
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            blockNetworkImage = false
            loadsImagesAutomatically = true
            
            // 视口配置
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            
            // 缩放配置 - 禁用缩放避免误触
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            
            // 媒体配置
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            
            // 性能优化
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            
            // 字体配置
            textZoom = 100
            minimumFontSize = 12
            
            // User Agent - 使用PC版获得更好体验
            userAgentString = buildUserAgent(userAgentString)
        }
        
        // 硬件加速 - 对于内存较小的设备可降级为软件渲染
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
        if (maxMemory >= 512) {
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        } else {
            // 低内存设备使用软件渲染避免OOM
            webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        }
    }
    
    /**
     * 构建适合电视的User Agent
     * 移除WebView标识,模拟真实Chrome浏览器
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
}
