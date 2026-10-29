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

    // passport_csrf_token 在未登录时也会下发，用它判断登录状态会误报
    private val LOGIN_KEYS = listOf("sessionid=", "sid_guard=", "uid_tt=", "sessionid_ss=")

    fun saveCookies(context: Context, url: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies.isNullOrEmpty()) return

            FileWriter(File(context.filesDir, COOKIE_FILE)).use { it.write(cookies) }

            if (hasLoginCookie(cookies)) {
                FileWriter(File(context.filesDir, LOGIN_COOKIE_FILE)).use { it.write(cookies) }
                Log.d(TAG, "Login cookies saved")
            }
            cookieManager.flush()
        } catch (e: IOException) {
            Log.e(TAG, "saveCookies error", e)
        } catch (t: Throwable) {
            Log.e(TAG, "saveCookies unexpected error", t)
        }
    }

    fun restoreCookies(context: Context, url: String) {
        try {
            val loginFile = File(context.filesDir, LOGIN_COOKIE_FILE)
            if (loginFile.exists()) {
                restoreFromFile(url, loginFile)
                if (isLoggedIn(url)) {
                    Log.d(TAG, "Login cookies restored successfully")
                    return
                }
            }

            val file = File(context.filesDir, COOKIE_FILE)
            if (file.exists()) restoreFromFile(url, file)
        } catch (e: IOException) {
            Log.e(TAG, "restoreCookies error", e)
        } catch (t: Throwable) {
            Log.e(TAG, "restoreCookies unexpected error", t)
        }
    }

    private fun restoreFromFile(url: String, file: File) {
        val cookies = FileReader(file).use { it.readText() }
        if (cookies.isBlank()) return

        val cookieManager = CookieManager.getInstance()
        val cookieArray = cookies.split("; ")
        for (cookie in cookieArray) {
            if (cookie.isNotBlank()) cookieManager.setCookie(url, cookie.trim())
        }
        cookieManager.flush()
        Log.d(TAG, "Restored ${cookieArray.size} cookies from ${file.name}")
    }

    fun clearAllCookies(context: Context) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            File(context.filesDir, COOKIE_FILE).delete()
            File(context.filesDir, LOGIN_COOKIE_FILE).delete()
        } catch (t: Throwable) {
            Log.e(TAG, "clearAllCookies error", t)
        }
    }

    fun isLoggedIn(url: String): Boolean {
        return try {
            hasLoginCookie(CookieManager.getInstance().getCookie(url))
        } catch (t: Throwable) {
            Log.e(TAG, "isLoggedIn error", t)
            false
        }
    }

    private fun hasLoginCookie(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        return LOGIN_KEYS.any { cookies.contains(it) }
    }

    fun getCookieInfo(url: String): String {
        return try {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (cookies.isNullOrEmpty()) return "No cookies"
            val loginStatus = if (hasLoginCookie(cookies)) "已登录" else "未登录"
            "Cookie数量: ${cookies.split("; ").size}, 状态: $loginStatus"
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
    }
}
