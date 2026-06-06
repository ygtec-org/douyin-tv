package com.douyin.tv.utils

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

object CookieHelper {
    
    private const val TAG = "CookieHelper"
    private const val COOKIE_FILE = "douyin_cookies.txt"
    private const val LOGIN_COOKIE_FILE = "douyin_login_cookies.txt"
    
    /**
     * 保存Cookie到文件
     */
    fun saveCookies(context: Context, url: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            
            if (!cookies.isNullOrEmpty()) {
                // 保存所有Cookie
                val file = File(context.filesDir, COOKIE_FILE)
                FileWriter(file).use { writer ->
                    writer.write(cookies)
                }
                
                // 检查是否包含登录Cookie
                if (cookies.contains("sessionid") || cookies.contains("passport_csrf_token")) {
                    val loginFile = File(context.filesDir, LOGIN_COOKIE_FILE)
                    FileWriter(loginFile).use { writer ->
                        writer.write(cookies)
                    }
                    Log.d(TAG, "Login cookies saved")
                }
                
                cookieManager.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "saveCookies error", e)
        }
    }
    
    /**
     * 从文件恢复Cookie
     */
    fun restoreCookies(context: Context, url: String) {
        try {
            // 优先恢复登录Cookie
            val loginFile = File(context.filesDir, LOGIN_COOKIE_FILE)
            if (loginFile.exists()) {
                restoreFromFile(context, url, loginFile)
                if (isLoggedIn(url)) {
                    Log.d(TAG, "Login cookies restored successfully")
                    return
                }
            }
            
            // 回退到普通Cookie
            val file = File(context.filesDir, COOKIE_FILE)
            if (file.exists()) {
                restoreFromFile(context, url, file)
            }
        } catch (e: IOException) {
            Log.e(TAG, "restoreCookies error", e)
        }
    }
    
    /**
     * 从指定文件恢复Cookie
     */
    private fun restoreFromFile(context: Context, url: String, file: File) {
        val cookies = FileReader(file).use { reader ->
            reader.readText()
        }
        
        val cookieManager = CookieManager.getInstance()
        val cookieArray = cookies.split("; ")
        
        for (cookie in cookieArray) {
            if (cookie.isNotBlank()) {
                cookieManager.setCookie(url, cookie.trim())
            }
        }
        
        cookieManager.flush()
        Log.d(TAG, "Restored ${cookieArray.size} cookies from ${file.name}")
    }
    
    /**
     * 清除所有Cookie
     */
    fun clearAllCookies(context: Context) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            File(context.filesDir, COOKIE_FILE).delete()
            File(context.filesDir, LOGIN_COOKIE_FILE).delete()
        } catch (e: Exception) {
            Log.e(TAG, "clearAllCookies error", e)
        }
    }
    
    /**
     * 检查是否已登录
     */
    fun isLoggedIn(url: String): Boolean {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            return !cookies.isNullOrEmpty() && 
                   (cookies.contains("sessionid") || cookies.contains("passport_csrf_token"))
        } catch (e: Exception) {
            Log.e(TAG, "isLoggedIn error", e)
            return false
        }
    }
    
    /**
     * 获取当前Cookie信息(用于调试)
     */
    fun getCookieInfo(url: String): String {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies.isNullOrEmpty()) return "No cookies"
            
            val cookieList = cookies.split("; ")
            val loginStatus = if (cookies.contains("sessionid")) "已登录" else "未登录"
            return "Cookie数量: ${cookieList.size}, 状态: $loginStatus"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }
}
