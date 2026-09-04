package com.harukisolodev.harukistream.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.harukiDataStore by preferencesDataStore(name = "haruki_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val AUTO_ANALYZE = booleanPreferencesKey("auto_analyze")
        val PLAY_AFTER = booleanPreferencesKey("play_after")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val PLAYBACK_QUALITY = stringPreferencesKey("playback_quality")
        val DOWNLOAD_SPEED_MODE = stringPreferencesKey("download_speed_mode")
    }

    val settings: Flow<AppSettings> = context.harukiDataStore.data.map { prefs ->
        AppSettings(
            defaultMode = runCatching {
                MediaMode.valueOf(prefs[Keys.DEFAULT_MODE] ?: MediaMode.VIDEO.name)
            }.getOrDefault(MediaMode.VIDEO),
            defaultQuality = prefs[Keys.DEFAULT_QUALITY] ?: "Best",
            autoAnalyze = prefs[Keys.AUTO_ANALYZE] ?: true,
            playAfterDownload = prefs[Keys.PLAY_AFTER] ?: false,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            autoplayNext = prefs[Keys.AUTOPLAY_NEXT] ?: true,
            playbackQuality = prefs[Keys.PLAYBACK_QUALITY] ?: "Auto",
            downloadSpeedMode = runCatching {
                DownloadSpeedMode.valueOf(prefs[Keys.DOWNLOAD_SPEED_MODE] ?: DownloadSpeedMode.AUTO.name)
            }.getOrDefault(DownloadSpeedMode.AUTO)
        )
    }

    suspend fun setDefaultMode(value: MediaMode) = context.harukiDataStore.edit { it[Keys.DEFAULT_MODE] = value.name }
    suspend fun setDefaultQuality(value: String) = context.harukiDataStore.edit { it[Keys.DEFAULT_QUALITY] = value }
    suspend fun setAutoAnalyze(value: Boolean) = context.harukiDataStore.edit { it[Keys.AUTO_ANALYZE] = value }
    suspend fun setPlayAfter(value: Boolean) = context.harukiDataStore.edit { it[Keys.PLAY_AFTER] = value }
    suspend fun setWifiOnly(value: Boolean) = context.harukiDataStore.edit { it[Keys.WIFI_ONLY] = value }
    suspend fun setAutoplayNext(value: Boolean) = context.harukiDataStore.edit { it[Keys.AUTOPLAY_NEXT] = value }
    suspend fun setPlaybackQuality(value: String) = context.harukiDataStore.edit { it[Keys.PLAYBACK_QUALITY] = value }
    suspend fun setDownloadSpeedMode(value: DownloadSpeedMode) = context.harukiDataStore.edit { it[Keys.DOWNLOAD_SPEED_MODE] = value.name }
    suspend fun reset() = context.harukiDataStore.edit { it.clear() }
}
