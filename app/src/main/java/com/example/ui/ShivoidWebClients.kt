package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.ShivoidPreferences

class ShivoidWebClient(
    private val context: Context,
    private val preferences: ShivoidPreferences,
    private val onPageTitleChanged: (String) -> Unit,
    private val onPageUrlChanged: (String) -> Unit,
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onErrorReceived: (String) -> Unit
) : WebViewClient() {

    private val shivoidJsContent: String by lazy {
        try {
            context.assets.open("shivoid.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("SHIVOID", "Failed to load shivoid.js asset", e)
            ""
        }
    }

    private fun injectShivoidJs(view: WebView?) {
        if (view != null && shivoidJsContent.isNotBlank()) {
            view.evaluateJavascript(shivoidJsContent, null)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: ""

        // Handle native intent schemes
        if (scheme != "http" && scheme != "https" && scheme != "file") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w("SHIVOID", "Unable to open external scheme: $url", e)
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingStateChanged(true)
        url?.let {
            onPageUrlChanged(it)
            // Inject shim early
            injectShivoidJs(view)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingStateChanged(false)
        url?.let {
            onPageUrlChanged(it)
            if (it.startsWith("http://") || it.startsWith("https://")) {
                preferences.lastUrl = it
            }
            injectShivoidJs(view)
        }
        view?.title?.let { onPageTitleChanged(it) }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                error?.description?.toString() ?: "Connection error"
            } else {
                "Connection error"
            }
            onErrorReceived(description)
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        val url = error?.url ?: ""
        // Gracefully allow localhost/LAN test servers if self-signed certs are used during local automation
        if (url.contains("localhost") || url.contains("127.0.0.1") || url.contains("192.168.")) {
            handler?.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }
}

class ShivoidChromeClient(
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived: (String) -> Unit,
    private val onShowFileChooserCallback: (
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ) -> Boolean
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        title?.let { onTitleReceived(it) }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            Log.d("SHIVOID_WEB", "[${it.messageLevel()}] ${it.message()} (at ${it.sourceId()}:${it.lineNumber()})")
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        return onShowFileChooserCallback(filePathCallback, fileChooserParams)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        // Automatically grant camera/mic permissions requested by WebView if app has system permissions
        request?.grant(request.resources)
    }
}
