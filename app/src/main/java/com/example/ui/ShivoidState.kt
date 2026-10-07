package com.example.ui

import com.example.data.Bookmark

data class ShivoidUiState(
    val currentUrl: String = "",
    val inputUrl: String = "",
    val pageTitle: String = "SHIVOID",
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isFullscreen: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isKeepScreenOn: Boolean = false,
    val errorMessage: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val showBookmarksSheet: Boolean = false,
    val showSettingsSheet: Boolean = false
)
