package com.douyin.tv.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import java.io.*

object CookieHelper {
    
    private const val COOKIE_FILE = "douyin_cookies.txt"
    
    /**
     * 保存Cookie到文件
     */
    fun saveCookies(context: Context, url: String) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)
        
        if (!cookies.isNullOrEmpty()) {
            try {
                val file = File(context.filesDir, COOKIE_FILE)
                FileWriter(file).use { writer ->
                    writer.write(cookies)
                }
                cookieManager.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 从文件恢复Cookie
     */
    fun restoreCookies(context: Context, url: String) {
        try {
            val file = File(context.filesDir, COOKIE_FILE)
            if (file.exists()) {
                val cookies = FileReader(file).use { reader ->
                    reader.readText()
                }
                
                val cookieManager = CookieManager.getInstance()
                val cookieArray = cookies.split("; ")
                
                for (cookie in cookieArray) {
                    cookieManager.setCookie(url, cookie)
                }
                
                cookieManager.flush()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    
    /**
     * 清除所有Cookie
     */
    fun clearAllCookies(context: Context) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        
        val file = File(context.filesDir, COOKIE_FILE)
        if (file.exists()) {
            file.delete()
        }
    }
    
    /**
     * 检查是否已登录
     */
    fun isLoggedIn(url: String): Boolean {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)
        return !cookies.isNullOrEmpty() && cookies.contains("sessionid")
    }
}
