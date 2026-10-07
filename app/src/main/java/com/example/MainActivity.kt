package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bridge.ShivoidBridgeHost
import com.example.bridge.ShivoidDeviceHelper
import com.example.bridge.ShivoidHttpHelper
import com.example.bridge.ShivoidNativeBridge
import com.example.bridge.ShivoidNotificationHelper
import com.example.bridge.ShivoidTtsManager
import com.example.data.ShivoidPreferences
import com.example.ui.ShivoidBookmarksSheet
import com.example.ui.ShivoidChromeClient
import com.example.ui.ShivoidSettingsSheet
import com.example.ui.ShivoidTopBar
import com.example.ui.ShivoidUiState
import com.example.ui.ShivoidWebClient
import com.example.ui.ShivoidWebViewContainer
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity(), ShivoidBridgeHost {

    private lateinit var preferences: ShivoidPreferences
    private lateinit var ttsManager: ShivoidTtsManager
    private lateinit var notificationHelper: ShivoidNotificationHelper
    private lateinit var deviceHelper: ShivoidDeviceHelper
    private lateinit var httpHelper: ShivoidHttpHelper
    private lateinit var nativeBridge: ShivoidNativeBridge

    private var activeWebView: WebView? = null

    // Callbacks for Native Bridge Camera and File Pickers
    private var pendingPhotoCallback: ((Boolean, String?, String?) -> Unit)? = null
    private var pendingFileCallback: ((Boolean, String?, String?, Long, String?, String?) -> Unit)? = null

    // Callback for standard HTML file chooser <input type="file">
    private var chromeFileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Compose UI State Holder
    private var uiState by mutableStateOf(ShivoidUiState())

    // Activity Result Launchers
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val dataUri = bitmapToDataUri(bitmap)
            pendingPhotoCallback?.invoke(true, dataUri, null)
        } else {
            pendingPhotoCallback?.invoke(false, null, "Photo capture cancelled")
        }
        pendingPhotoCallback = null
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val info = extractFileInfo(this, uri)
            pendingFileCallback?.invoke(true, info.name, info.mime, info.size, info.dataUri, null)
        } else {
            pendingFileCallback?.invoke(false, null, null, 0L, null, "File selection cancelled")
        }
        pendingFileCallback = null
    }

    private val chromeFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            if (clipData != null) {
                (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }.toTypedArray()
            } else {
                result.data?.data?.let { arrayOf(it) }
            }
        } else {
            null
        }
        chromeFileChooserCallback?.onReceiveValue(uris)
        chromeFileChooserCallback = null
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePhotoLauncher.launch(null)
        } else {
            pendingPhotoCallback?.invoke(false, null, "Camera permission denied by user")
            pendingPhotoCallback = null
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Processed automatically by system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = ShivoidPreferences(this)
        ttsManager = ShivoidTtsManager(this) { utteranceId ->
            // Dispatches TTS completed event to web
            evaluateJavascript("window.__shivoid_dispatch && window.__shivoid_dispatch({ callbackId: '$utteranceId', success: true });")
        }
        notificationHelper = ShivoidNotificationHelper(this)
        deviceHelper = ShivoidDeviceHelper(this)
        httpHelper = ShivoidHttpHelper()

        nativeBridge = ShivoidNativeBridge(
            context = this,
            host = this,
            preferences = preferences,
            ttsManager = ttsManager,
            notificationHelper = notificationHelper,
            deviceHelper = deviceHelper,
            httpHelper = httpHelper,
            scope = lifecycleScope
        )

        // Request Notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Apply saved keep screen on preference
        setKeepScreenOn(preferences.isKeepScreenOn)

        val initialUrl = preferences.lastUrl.ifBlank { ShivoidPreferences.DEFAULT_HOME_URL }
        uiState = uiState.copy(
            currentUrl = initialUrl,
            inputUrl = initialUrl,
            isKeepScreenOn = preferences.isKeepScreenOn,
            isDesktopMode = preferences.isDesktopMode,
            bookmarks = preferences.getBookmarks()
        )

        setContent {
            MyApplicationTheme {
                ShivoidMainScreen()
            }
        }
    }

    @Composable
    private fun ShivoidMainScreen() {
        val coroutineScope = rememberCoroutineScope()

        val webViewClient = remember {
            ShivoidWebClient(
                context = this@MainActivity,
                preferences = preferences,
                onPageTitleChanged = { title ->
                    uiState = uiState.copy(pageTitle = title)
                },
                onPageUrlChanged = { url ->
                    uiState = uiState.copy(
                        currentUrl = url,
                        inputUrl = url,
                        canGoBack = activeWebView?.canGoBack() ?: false,
                        canGoForward = activeWebView?.canGoForward() ?: false,
                        errorMessage = null
                    )
                },
                onLoadingStateChanged = { loading ->
                    uiState = uiState.copy(
                        isLoading = loading,
                        canGoBack = activeWebView?.canGoBack() ?: false,
                        canGoForward = activeWebView?.canGoForward() ?: false
                    )
                },
                onErrorReceived = { errorMsg ->
                    uiState = uiState.copy(errorMessage = errorMsg, isLoading = false)
                }
            )
        }

        val webChromeClient = remember {
            ShivoidChromeClient(
                onProgressChanged = { newProgress ->
                    uiState = uiState.copy(
                        progress = newProgress / 100f,
                        isLoading = newProgress < 100
                    )
                },
                onTitleReceived = { title ->
                    uiState = uiState.copy(pageTitle = title)
                },
                onShowFileChooserCallback = { filePathCallback, fileChooserParams ->
                    chromeFileChooserCallback?.onReceiveValue(null)
                    chromeFileChooserCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }
                    try {
                        chromeFileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        chromeFileChooserCallback = null
                        false
                    }
                }
            )
        }

        // Handle Android Back Navigation
        BackHandler(enabled = uiState.isFullscreen || uiState.canGoBack) {
            when {
                uiState.isFullscreen -> setFullscreen(false)
                uiState.canGoBack -> goBackWebView()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Top Navigation Bar (Hidden in Fullscreen)
                if (!uiState.isFullscreen) {
                    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    ShivoidTopBar(
                        state = uiState,
                        onUrlInputChange = { uiState = uiState.copy(inputUrl = it) },
                        onLoadUrl = { url -> loadUrl(url) },
                        onBackClick = { goBackWebView() },
                        onForwardClick = { goForwardWebView() },
                        onRefreshClick = { reloadWebView() },
                        onStopClick = { activeWebView?.stopLoading() },
                        onHomeClick = { loadUrl(ShivoidPreferences.DEFAULT_HOME_URL) },
                        onFullscreenToggle = { setFullscreen(!uiState.isFullscreen) },
                        onBookmarksClick = { uiState = uiState.copy(showBookmarksSheet = true) },
                        onSettingsClick = { uiState = uiState.copy(showSettingsSheet = true) },
                        modifier = Modifier.padding(top = statusBarPadding)
                    )
                }

                // WebView Container
                ShivoidWebViewContainer(
                    state = uiState,
                    webViewClient = webViewClient,
                    webChromeClient = webChromeClient,
                    nativeBridge = nativeBridge,
                    onWebViewCreated = { webView ->
                        activeWebView = webView
                        webView.loadUrl(uiState.currentUrl)
                    },
                    onRetryClick = {
                        uiState = uiState.copy(errorMessage = null)
                        activeWebView?.reload()
                    },
                    onOpenPlaygroundClick = {
                        uiState = uiState.copy(errorMessage = null)
                        loadUrl(ShivoidPreferences.DEFAULT_HOME_URL)
                    },
                    onExitFullscreen = { setFullscreen(false) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Bookmarks Bottom Sheet
        if (uiState.showBookmarksSheet) {
            ShivoidBookmarksSheet(
                bookmarks = uiState.bookmarks,
                currentUrl = uiState.currentUrl,
                onSelectBookmark = { url ->
                    loadUrl(url)
                    uiState = uiState.copy(showBookmarksSheet = false)
                },
                onAddBookmark = { title, url ->
                    preferences.addBookmark(title, url)
                    uiState = uiState.copy(bookmarks = preferences.getBookmarks())
                    Toast.makeText(this@MainActivity, "Bookmark added: $title", Toast.LENGTH_SHORT).show()
                },
                onDeleteBookmark = { url ->
                    preferences.removeBookmark(url)
                    uiState = uiState.copy(bookmarks = preferences.getBookmarks())
                },
                onDismiss = { uiState = uiState.copy(showBookmarksSheet = false) }
            )
        }

        // Settings Bottom Sheet
        if (uiState.showSettingsSheet) {
            ShivoidSettingsSheet(
                isDesktopMode = uiState.isDesktopMode,
                isKeepScreenOn = uiState.isKeepScreenOn,
                onToggleDesktopMode = { desktop ->
                    preferences.isDesktopMode = desktop
                    uiState = uiState.copy(isDesktopMode = desktop)
                    activeWebView?.reload()
                },
                onToggleKeepScreenOn = { screenOn ->
                    preferences.isKeepScreenOn = screenOn
                    uiState = uiState.copy(isKeepScreenOn = screenOn)
                    setKeepScreenOn(screenOn)
                },
                onClearCache = {
                    activeWebView?.clearCache(true)
                    activeWebView?.clearHistory()
                    Toast.makeText(this@MainActivity, "Cache & history cleared", Toast.LENGTH_SHORT).show()
                },
                onLoadPlayground = {
                    loadUrl(ShivoidPreferences.DEFAULT_HOME_URL)
                },
                onDismiss = { uiState = uiState.copy(showSettingsSheet = false) }
            )
        }
    }

    // ==========================================
    // ShivoidBridgeHost Implementation
    // ==========================================

    override fun runOnMain(block: () -> Unit) {
        runOnUiThread(block)
    }

    override fun evaluateJavascript(script: String) {
        runOnUiThread {
            activeWebView?.evaluateJavascript(script, null)
        }
    }

    override fun requestTakePhoto(callback: (success: Boolean, dataUri: String?, error: String?) -> Unit) {
        runOnUiThread {
            pendingPhotoCallback = callback
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                takePhotoLauncher.launch(null)
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun requestPickFile(
        mimeType: String,
        callback: (success: Boolean, name: String?, mime: String?, size: Long, dataUri: String?, error: String?) -> Unit
    ) {
        runOnUiThread {
            pendingFileCallback = callback
            val targetMime = if (mimeType.isBlank()) "*/*" else mimeType
            try {
                pickFileLauncher.launch(targetMime)
            } catch (e: Exception) {
                callback(false, null, null, 0L, null, e.message ?: "Failed to open file picker")
                pendingFileCallback = null
            }
        }
    }

    override fun setFullscreen(fullscreen: Boolean) {
        uiState = uiState.copy(isFullscreen = fullscreen)
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        runOnUiThread {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun reloadWebView() {
        runOnUiThread { activeWebView?.reload() }
    }

    override fun goBackWebView() {
        runOnUiThread {
            if (activeWebView?.canGoBack() == true) {
                activeWebView?.goBack()
            }
        }
    }

    override fun goForwardWebView() {
        runOnUiThread {
            if (activeWebView?.canGoForward() == true) {
                activeWebView?.goForward()
            }
        }
    }

    override fun canGoBack(): Boolean = activeWebView?.canGoBack() ?: false

    override fun canGoForward(): Boolean = activeWebView?.canGoForward() ?: false

    override fun loadUrl(url: String) {
        val trimmed = url.trim()
        val finalUrl = when {
            trimmed.isBlank() -> ShivoidPreferences.DEFAULT_HOME_URL
            trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") -> trimmed
            trimmed.startsWith("192.168.") || trimmed.startsWith("10.") || trimmed.startsWith("127.0.0.1") || trimmed.startsWith("localhost") -> "http://$trimmed"
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=$trimmed"
        }

        uiState = uiState.copy(inputUrl = finalUrl, errorMessage = null)
        runOnUiThread {
            activeWebView?.loadUrl(finalUrl)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        activeWebView?.destroy()
        activeWebView = null
    }

    companion object {
        private fun bitmapToDataUri(bitmap: Bitmap): String {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return "data:image/jpeg;base64,$b64"
        }

        private data class FileInfo(val name: String, val mime: String, val size: Long, val dataUri: String)

        private fun extractFileInfo(context: Context, uri: Uri): FileInfo {
            val cr = context.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            var name = "unknown_file"
            var size = 0L

            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }

            val bytes = try {
                cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (size == 0L) size = bytes.size.toLong()

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:$mime;base64,$b64"
            return FileInfo(name, mime, size, dataUri)
        }
    }
}
