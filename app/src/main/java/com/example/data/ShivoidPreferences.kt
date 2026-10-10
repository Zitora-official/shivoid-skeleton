package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(
    val title: String,
    val url: String
)

class ShivoidPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shivoid_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_URL = "key_last_url"
        private const val KEY_BOOKMARKS = "key_bookmarks"
        private const val KEY_KEEP_SCREEN_ON = "key_keep_screen_on"
        private const val KEY_DESKTOP_MODE = "key_desktop_mode"
        private const val KEY_AUTOMATE_ENDPOINT = "key_automate_endpoint"
        const val DEFAULT_HOME_URL = "file:///android_asset/shivoid_home.html"
        const val DEFAULT_AUTOMATE_ENDPOINT = "http://127.0.0.1:8080/"
    }

    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, DEFAULT_HOME_URL) ?: DEFAULT_HOME_URL
        set(value) {
            if (value.isNotBlank()) {
                prefs.edit().putString(KEY_LAST_URL, value).apply()
            }
        }

    var isKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var isDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    var automateEndpoint: String
        get() = prefs.getString(KEY_AUTOMATE_ENDPOINT, DEFAULT_AUTOMATE_ENDPOINT) ?: DEFAULT_AUTOMATE_ENDPOINT
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) {
                prefs.edit().putString(KEY_AUTOMATE_ENDPOINT, trimmed).apply()
            }
        }

    fun getBookmarks(): List<Bookmark> {
        val jsonStr = prefs.getString(KEY_BOOKMARKS, null) ?: return getDefaultBookmarks()
        val list = mutableListOf<Bookmark>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(Bookmark(obj.getString("title"), obj.getString("url")))
            }
        } catch (_: Exception) {
            return getDefaultBookmarks()
        }
        return list
    }

    fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach {
            val obj = JSONObject()
            obj.put("title", it.title)
            obj.put("url", it.url)
            array.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    fun addBookmark(title: String, url: String) {
        val current = getBookmarks().toMutableList()
        if (current.none { it.url.equals(url, ignoreCase = true) }) {
            current.add(Bookmark(title, url))
            saveBookmarks(current)
        }
    }

    fun removeBookmark(url: String) {
        val filtered = getBookmarks().filterNot { it.url.equals(url, ignoreCase = true) }
        saveBookmarks(filtered)
    }

    private fun getDefaultBookmarks(): List<Bookmark> {
        return listOf(
            Bookmark("SHI.V01D Playground", DEFAULT_HOME_URL),
            Bookmark("Local Automate Listener", DEFAULT_AUTOMATE_ENDPOINT),
            Bookmark("Local Network Endpoint", "http://192.168.1.1:8080"),
            Bookmark("HttpBin Test API", "https://httpbin.org/get")
        )
    }
}
