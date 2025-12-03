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

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            
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
                // 视频结束时自动播放下一个
                simulateSwipeUp()
            }
        }
        
        @JavascriptInterface
        fun onVideoPlaying() {
            isVideoPlaying = true
        }
        
        @JavascriptInterface
        fun onVideoPaused() {
            isVideoPlaying = false
        }
    }

    private fun injectAutoPlayScript() {
        val script = """
            (function() {
                // 监听视频播放状态
                function monitorVideo() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            
                            // 视频结束时自动播放下一个
                            video.addEventListener('ended', function() {
                                console.log('Video ended, playing next...');
                                AndroidInterface.onVideoEnded();
                            });
                            
                            video.addEventListener('playing', function() {
                                AndroidInterface.onVideoPlaying();
                            });
                            
                            video.addEventListener('pause', function() {
                                AndroidInterface.onVideoPaused();
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
                
                // 优化触摸和点击事件
                document.addEventListener('click', function(e) {
                    console.log('Click detected');
                }, true);
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
