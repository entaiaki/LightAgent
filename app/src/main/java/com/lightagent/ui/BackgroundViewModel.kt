package com.lightagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class BackgroundSource {
    data class Asset(val fileName: String) : BackgroundSource()
    data class Custom(val uri: Uri) : BackgroundSource()
    object SolidColor : BackgroundSource()
}

// ── 背景套系定义 ──────────────────────────────────────────────────────────────
// 每个套系有名字、emoji、以及对应的图片文件名列表
// 图片放在 assets/backgrounds/{套系文件夹}/ 下
data class BackgroundTheme(
    val id       : String,
    val name     : String,
    val emoji    : String,
    val folder   : String,         // assets/backgrounds/{folder}/
    val fileNames: List<String>    // folder 内的文件名
)

val ALL_BACKGROUND_THEMES = listOf(
    BackgroundTheme(
        id        = "night",
        name      = "深夜星空",
        emoji     = "🌌",
        folder    = "night",
        fileNames = (1..6).map { "night_$it.png" }
    ),
    BackgroundTheme(
        id        = "sakura",
        name      = "樱花物语",
        emoji     = "🌸",
        folder    = "sakura",
        fileNames = (1..6).map { "sakura_$it.png" }
    ),
    BackgroundTheme(
        id        = "ocean",
        name      = "深海之境",
        emoji     = "🌊",
        folder    = "ocean",
        fileNames = (1..6).map { "ocean_$it.png" }
    ),
    BackgroundTheme(
        id        = "forest",
        name      = "幽静森林",
        emoji     = "🌿",
        folder    = "forest",
        fileNames = (1..6).map { "forest_$it.png" }
    ),
    BackgroundTheme(
        id        = "cyberpunk",
        name      = "赛博霓虹",
        emoji     = "🌃",
        folder    = "cyberpunk",
        fileNames = (1..6).map { "cyberpunk_$it.png" }
    ),
    BackgroundTheme(
        id        = "plain",
        name      = "纯色渐变",
        emoji     = "🎨",
        folder    = "",            // 无图片，用渐变色
        fileNames = emptyList()
    )
)

class BackgroundViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)

    private val _background = MutableStateFlow<BackgroundSource>(loadSaved())
    val background: StateFlow<BackgroundSource> = _background

    // 当前选中的套系
    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<BackgroundTheme> = _currentTheme

    // 所有套系
    val themes: List<BackgroundTheme> = ALL_BACKGROUND_THEMES

    // ── 切换套系（随机选一张该套系的图）────────────────────────────────────
    fun selectTheme(theme: BackgroundTheme) {
        _currentTheme.value = theme
        prefs.edit().putString(KEY_THEME_ID, theme.id).apply()

        if (theme.fileNames.isEmpty()) {
            // 纯色套系 → 用渐变色兜底
            _background.value = BackgroundSource.SolidColor
            prefs.edit().putString(KEY_TYPE, TYPE_SOLID).apply()
        } else {
            val file = theme.fileNames.random()
            val path = "${theme.folder}/$file"
            _background.value = BackgroundSource.Asset(path)
            saveAsset(path)
        }
    }

    // ── 在当前套系内随机切换 ─────────────────────────────────────────────────
    fun randomInCurrentTheme() {
        val theme = _currentTheme.value
        if (theme.fileNames.isEmpty()) return
        val current = (_background.value as? BackgroundSource.Asset)?.fileName
        val candidates = theme.fileNames.map { "${theme.folder}/$it" }.filter { it != current }
        val next = candidates.randomOrNull() ?: theme.fileNames.first().let { "${theme.folder}/$it" }
        _background.value = BackgroundSource.Asset(next)
        saveAsset(next)
    }

    // ── 选中套系内指定图片 ───────────────────────────────────────────────────
    fun selectSpecificAsset(assetPath: String) {
        _background.value = BackgroundSource.Asset(assetPath)
        saveAsset(assetPath)
    }

    // ── 用户自选图片 ─────────────────────────────────────────────────────────
    fun setCustomBackground(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        } catch (_: Exception) {}
        _background.value = BackgroundSource.Custom(uri)
        prefs.edit()
            .putString(KEY_TYPE, TYPE_CUSTOM)
            .putString(KEY_URI, uri.toString())
            .apply()
    }

    // ── 恢复默认 ─────────────────────────────────────────────────────────────
    fun resetToDefault() {
        val defaultTheme = ALL_BACKGROUND_THEMES.first()
        selectTheme(defaultTheme)
    }

    // ── 兼容旧接口 ───────────────────────────────────────────────────────────
    fun randomBackground() = randomInCurrentTheme()

    // ── 持久化 ───────────────────────────────────────────────────────────────
    private fun saveAsset(path: String) {
        prefs.edit()
            .putString(KEY_TYPE, TYPE_ASSET)
            .putString(KEY_ASSET, path)
            .apply()
    }

    private fun loadSaved(): BackgroundSource {
        return when (prefs.getString(KEY_TYPE, TYPE_SOLID)) {
            TYPE_CUSTOM -> {
                val uriStr = prefs.getString(KEY_URI, null)
                if (uriStr != null) BackgroundSource.Custom(Uri.parse(uriStr))
                else BackgroundSource.SolidColor
            }
            TYPE_ASSET -> {
                val asset = prefs.getString(KEY_ASSET, "") ?: ""
                if (asset.isNotBlank()) BackgroundSource.Asset(asset)
                else BackgroundSource.SolidColor
            }
            else -> BackgroundSource.SolidColor
        }
    }

    private fun loadSavedTheme(): BackgroundTheme {
        val savedId = prefs.getString(KEY_THEME_ID, ALL_BACKGROUND_THEMES.first().id) ?: ""
        return ALL_BACKGROUND_THEMES.find { it.id == savedId } ?: ALL_BACKGROUND_THEMES.first()
    }

    companion object {
        private const val KEY_TYPE     = "bg_type"
        private const val KEY_ASSET    = "bg_asset"
        private const val KEY_URI      = "bg_uri"
        private const val KEY_THEME_ID = "bg_theme_id"
        private const val TYPE_ASSET   = "asset"
        private const val TYPE_CUSTOM  = "custom"
        private const val TYPE_SOLID   = "solid"
    }
}
