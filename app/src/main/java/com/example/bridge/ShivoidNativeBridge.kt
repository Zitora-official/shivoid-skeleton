package com.example.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.example.data.ShivoidPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

interface ShivoidBridgeHost {
    fun runOnMain(block: () -> Unit)
    fun evaluateJavascript(script: String)
    fun requestTakePhoto(callback: (success: Boolean, dataUri: String?, error: String?) -> Unit)
    fun requestPickFile(mimeType: String, callback: (success: Boolean, name: String?, mime: String?, size: Long, dataUri: String?, error: String?) -> Unit)
    fun setFullscreen(fullscreen: Boolean)
    fun setKeepScreenOn(enabled: Boolean)
    fun reloadWebView()
    fun goBackWebView()
    fun goForwardWebView()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun loadUrl(url: String)
}

class ShivoidNativeBridge(
    private val context: Context,
    private val host: ShivoidBridgeHost,
    private val preferences: ShivoidPreferences,
    private val ttsManager: ShivoidTtsManager,
    private val notificationHelper: ShivoidNotificationHelper,
    private val deviceHelper: ShivoidDeviceHelper,
    private val httpHelper: ShivoidHttpHelper,
    private val scope: CoroutineScope
) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val clipboard: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun dispatchResult(callbackId: String, success: Boolean, data: JSONObject? = null, error: String? = null) {
        val root = JSONObject()
        root.put("callbackId", callbackId)
        root.put("success", success)
        if (data != null) root.put("data", data)
        if (error != null) root.put("error", error)

        val jsCall = "window.__shivoid_dispatch && window.__shivoid_dispatch(${root});"
        host.runOnMain {
            host.evaluateJavascript(jsCall)
        }
    }

    // ==========================================
    // 1. Text-To-Speech (TTS)
    // ==========================================

    @JavascriptInterface
    fun speak(text: String?, lang: String?, pitch: Float, rate: Float, callbackId: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return ttsManager.speak(text, lang, pitch, rate, callbackId)
    }

    @JavascriptInterface
    fun stopTTS(): Boolean {
        return ttsManager.stop()
    }

    @JavascriptInterface
    fun isSpeaking(): Boolean {
        return ttsManager.isSpeaking()
    }

    // ==========================================
    // 2. Vibration & Haptics
    // ==========================================

    @JavascriptInterface
    fun vibrate(durationMs: Long): Boolean {
        val v = vibrator ?: return false
        val duration = durationMs.coerceIn(10L, 5000L)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun vibratePattern(patternJson: String?, repeatIndex: Int): Boolean {
        val v = vibrator ?: return false
        if (patternJson.isNullOrBlank()) return false
        return try {
            val jsonArr = JSONArray(patternJson)
            val timings = LongArray(jsonArr.length())
            for (i in 0 until jsonArr.length()) {
                timings[i] = jsonArr.getLong(i).coerceAtLeast(0L)
            }
            val repeat = repeatIndex.coerceIn(-1, timings.size - 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, repeat))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, repeat)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun cancelVibration(): Boolean {
        return try {
            vibrator?.cancel()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // 3. Notifications
    // ==========================================

    @JavascriptInterface
    fun showNotification(title: String?, message: String?, id: Int, channelId: String?): Boolean {
        val safeTitle = title ?: "SHIVOID"
        val safeMessage = message ?: ""
        return notificationHelper.showNotification(safeTitle, safeMessage, id, channelId)
    }

    @JavascriptInterface
    fun cancelNotification(id: Int): Boolean {
        return notificationHelper.cancelNotification(id)
    }

    // ==========================================
    // 4. HTTP & Automate Bridge
    // ==========================================

    @JavascriptInterface
    fun httpGet(url: String?, headersJson: String?, callbackId: String) {
        if (url.isNullOrBlank()) {
            dispatchResult(callbackId, false, error = "URL is required")
            return
        }
        scope.launch {
            val response = httpHelper.executeGet(url, headersJson)
            val success = response.optBoolean("success", false)
            dispatchResult(callbackId, success, response, if (!success) response.optString("error") else null)
        }
    }

    @JavascriptInterface
    fun httpPost(url: String?, body: String?, contentType: String?, headersJson: String?, callbackId: String) {
        if (url.isNullOrBlank()) {
            dispatchResult(callbackId, false, error = "URL is required")
            return
        }
        scope.launch {
            val response = httpHelper.executePost(url, body, contentType, headersJson)
            val success = response.optBoolean("success", false)
            dispatchResult(callbackId, success, response, if (!success) response.optString("error") else null)
        }
    }

    @JavascriptInterface
    fun callAutomate(endpointUrl: String?, payloadJson: String?, callbackId: String) {
        if (endpointUrl.isNullOrBlank()) {
            dispatchResult(callbackId, false, error = "Automate endpoint URL is required")
            return
        }
        scope.launch {
            val response = httpHelper.callAutomate(endpointUrl, payloadJson)
            val success = response.optBoolean("success", false)
            dispatchResult(callbackId, success, response, if (!success) response.optString("error") else null)
        }
    }

    // ==========================================
    // 5. Battery & Device Info
    // ==========================================

    @JavascriptInterface
    fun getBatteryInfo(): String {
        return deviceHelper.getBatteryInfoJson()
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        return deviceHelper.getDeviceInfoJson()
    }

    // ==========================================
    // 6. Clipboard
    // ==========================================

    @JavascriptInterface
    fun copyToClipboard(text: String?): Boolean {
        val safeText = text ?: ""
        return try {
            val clip = ClipData.newPlainText("SHIVOID", safeText)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun readClipboard(): String {
        return try {
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context).toString()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // ==========================================
    // 7. Camera & File Operations
    // ==========================================

    @JavascriptInterface
    fun takePhoto(callbackId: String) {
        host.requestTakePhoto { success, dataUri, error ->
            val obj = JSONObject()
            if (dataUri != null) obj.put("dataUri", dataUri)
            dispatchResult(callbackId, success, obj, error)
        }
    }

    @JavascriptInterface
    fun pickFile(mimeType: String?, callbackId: String) {
        val safeMime = if (mimeType.isNullOrBlank()) "*/*" else mimeType
        host.requestPickFile(safeMime) { success, name, mime, size, dataUri, error ->
            val obj = JSONObject()
            if (name != null) obj.put("name", name)
            if (mime != null) obj.put("mime", mime)
            obj.put("size", size)
            if (dataUri != null) obj.put("dataUri", dataUri)
            dispatchResult(callbackId, success, obj, error)
        }
    }

    // ==========================================
    // 8. Navigation & External Apps
    // ==========================================

    @JavascriptInterface
    fun openExternal(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun launchApp(packageName: String?, fallbackUrl: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else if (!fallbackUrl.isNullOrBlank()) {
                openExternal(fallbackUrl)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun shareText(text: String?, title: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, title ?: "Share via SHIVOID").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // 9. App & WebView Controls
    // ==========================================

    @JavascriptInterface
    fun showToast(message: String?) {
        if (!message.isNullOrBlank()) {
            host.runOnMain {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun setFullscreen(fullscreen: Boolean) {
        host.runOnMain { host.setFullscreen(fullscreen) }
    }

    @JavascriptInterface
    fun keepScreenOn(enabled: Boolean) {
        host.runOnMain { host.setKeepScreenOn(enabled) }
    }

    @JavascriptInterface
    fun reload() {
        host.runOnMain { host.reloadWebView() }
    }

    @JavascriptInterface
    fun goBack() {
        host.runOnMain { host.goBackWebView() }
    }

    @JavascriptInterface
    fun goForward() {
        host.runOnMain { host.goForwardWebView() }
    }

    @JavascriptInterface
    fun canGoBack(): Boolean {
        return host.canGoBack()
    }

    @JavascriptInterface
    fun canGoForward(): Boolean {
        return host.canGoForward()
    }

    @JavascriptInterface
    fun getLastUrl(): String {
        return preferences.lastUrl
    }

    @JavascriptInterface
    fun setLastUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            preferences.lastUrl = url
        }
    }

    @JavascriptInterface
    fun getVersion(): String {
        return "1.0.0"
    }
}
