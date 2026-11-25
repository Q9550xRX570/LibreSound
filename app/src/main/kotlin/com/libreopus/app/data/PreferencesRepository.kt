// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.libreopus.app.ui.theme.ThemeMode
import com.libreopus.audio.api.ExportFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "libreopus_prefs")

data class RecentFileEntry(
    val uriString: String,
    val displayName: String,
    val formatLabel: String,
    val durationMs: Long,
    val sampleRate: Int,
    val bitrateKbps: Int,
)

enum class AppLanguage(val code: String) {
    SYSTEM("system"),
    EN("en"),
    TR("tr"),
}

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val defaultExportFormat: ExportFormat = ExportFormat.OPUS,
    val defaultBitrateKbps: Int = 128,
    val recentFiles: List<RecentFileEntry> = emptyList(),
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val LANGUAGE = stringPreferencesKey("language")
        val DEFAULT_FORMAT = stringPreferencesKey("default_export_format")
        val DEFAULT_BITRATE = intPreferencesKey("default_bitrate_kbps")
        val RECENT_FILES = stringPreferencesKey("recent_files_v1")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            amoledBlack = prefs[Keys.AMOLED_BLACK] ?: false,
            language = AppLanguage.entries.firstOrNull { it.code == prefs[Keys.LANGUAGE] }
                ?: AppLanguage.SYSTEM,
            defaultExportFormat = runCatching {
                ExportFormat.valueOf(prefs[Keys.DEFAULT_FORMAT] ?: ExportFormat.OPUS.name)
            }.getOrDefault(ExportFormat.OPUS),
            defaultBitrateKbps = prefs[Keys.DEFAULT_BITRATE] ?: 128,
            recentFiles = decodeRecents(prefs[Keys.RECENT_FILES].orEmpty()),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMOLED_BLACK] = enabled }
    }

    suspend fun setLanguage(lang: AppLanguage) {
        context.dataStore.edit { it[Keys.LANGUAGE] = lang.code }
    }

    suspend fun setDefaultExportFormat(format: ExportFormat) {
        context.dataStore.edit { it[Keys.DEFAULT_FORMAT] = format.name }
    }

    suspend fun setDefaultBitrate(kbps: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_BITRATE] = kbps }
    }

    suspend fun addRecentFile(entry: RecentFileEntry) {
        context.dataStore.edit { prefs ->
            val current = decodeRecents(prefs[Keys.RECENT_FILES].orEmpty())
                .filterNot { it.uriString == entry.uriString }
            val updated = (listOf(entry) + current).take(12)
            prefs[Keys.RECENT_FILES] = encodeRecents(updated)
        }
    }

    suspend fun clearRecents() {
        context.dataStore.edit { it.remove(Keys.RECENT_FILES) }
    }

    private fun encodeRecents(list: List<RecentFileEntry>): String =
        list.joinToString("\n") { e ->
            listOf(
                e.uriString.replace("|", "%7C").replace("\n", ""),
                e.displayName.replace("|", "_").replace("\n", " "),
                e.formatLabel.replace("|", "_"),
                e.durationMs.toString(),
                e.sampleRate.toString(),
                e.bitrateKbps.toString(),
            ).joinToString("|")
        }

    private fun decodeRecents(raw: String): List<RecentFileEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 6) return@mapNotNull null
            RecentFileEntry(
                uriString = parts[0],
                displayName = parts[1],
                formatLabel = parts[2],
                durationMs = parts[3].toLongOrNull() ?: 0L,
                sampleRate = parts[4].toIntOrNull() ?: 48000,
                bitrateKbps = parts[5].toIntOrNull() ?: 0,
            )
        }
    }
}
