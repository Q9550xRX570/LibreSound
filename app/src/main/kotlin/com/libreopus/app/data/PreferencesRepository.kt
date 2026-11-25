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
    val ytdlpUseCookies: Boolean = true,
    val ytdlpAudioPreference: String = "AUTO_BEST",
    val ytdlpForceIpv4: Boolean = false,
    val ytdlpGeoBypass: Boolean = true,
    val ytdlpProxyUrl: String = "",
    val ytdlpUserAgent: String = "",
    val ytdlpUpdateChannel: String = "STABLE",
    val ytdlpCustomArgs: String = "-f bestaudio --no-playlist",
    val ytdlpAllowPlaylist: Boolean = false,
    val ytdlpFilenameTemplate: String = "TITLE",
    val ytdlpCustomFilenameTemplate: String = "%(title).80B.%(ext)s",
    val ytdlpYoutubeClient: String = "AUTO",
    val ytdlpConcurrentFragments: Int = 4,
    val ytdlpNoCheckCerts: Boolean = false,
    val ytdlpWriteSubs: Boolean = false,
    val ytdlpRateLimit: String = "",
    val ytdlpExtraExtractorArgs: String = "",
    val ytdlpReferer: String = "",
    val ytdlpCommandTemplatesJson: String = "",
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
        val YTDLP_USE_COOKIES = booleanPreferencesKey("ytdlp_use_cookies")
        val YTDLP_AUDIO_PREF = stringPreferencesKey("ytdlp_audio_pref")
        val YTDLP_FORCE_IPV4 = booleanPreferencesKey("ytdlp_force_ipv4")
        val YTDLP_GEO_BYPASS = booleanPreferencesKey("ytdlp_geo_bypass")
        val YTDLP_PROXY_URL = stringPreferencesKey("ytdlp_proxy_url")
        val YTDLP_USER_AGENT = stringPreferencesKey("ytdlp_user_agent")
        val YTDLP_UPDATE_CHANNEL = stringPreferencesKey("ytdlp_update_channel")
        val YTDLP_CUSTOM_ARGS = stringPreferencesKey("ytdlp_custom_args")
        val YTDLP_ALLOW_PLAYLIST = booleanPreferencesKey("ytdlp_allow_playlist")
        val YTDLP_FILENAME_TEMPLATE = stringPreferencesKey("ytdlp_filename_template")
        val YTDLP_CUSTOM_FILENAME_TEMPLATE = stringPreferencesKey("ytdlp_custom_filename_template")
        val YTDLP_YOUTUBE_CLIENT = stringPreferencesKey("ytdlp_youtube_client")
        val YTDLP_CONCURRENT_FRAGMENTS = intPreferencesKey("ytdlp_concurrent_fragments")
        val YTDLP_NO_CHECK_CERTS = booleanPreferencesKey("ytdlp_no_check_certs")
        val YTDLP_WRITE_SUBS = booleanPreferencesKey("ytdlp_write_subs")
        val YTDLP_RATE_LIMIT = stringPreferencesKey("ytdlp_rate_limit")
        val YTDLP_EXTRA_EXTRACTOR_ARGS = stringPreferencesKey("ytdlp_extra_extractor_args")
        val YTDLP_REFERER = stringPreferencesKey("ytdlp_referer")
        val YTDLP_COMMAND_TEMPLATES = stringPreferencesKey("ytdlp_command_templates_v1")
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
            ytdlpUseCookies = prefs[Keys.YTDLP_USE_COOKIES] ?: true,
            ytdlpAudioPreference = prefs[Keys.YTDLP_AUDIO_PREF] ?: "AUTO_BEST",
            ytdlpForceIpv4 = prefs[Keys.YTDLP_FORCE_IPV4] ?: false,
            ytdlpGeoBypass = prefs[Keys.YTDLP_GEO_BYPASS] ?: true,
            ytdlpProxyUrl = prefs[Keys.YTDLP_PROXY_URL].orEmpty(),
            ytdlpUserAgent = prefs[Keys.YTDLP_USER_AGENT].orEmpty(),
            ytdlpUpdateChannel = prefs[Keys.YTDLP_UPDATE_CHANNEL] ?: "STABLE",
            ytdlpCustomArgs = prefs[Keys.YTDLP_CUSTOM_ARGS] ?: "-f bestaudio --no-playlist",
            ytdlpAllowPlaylist = prefs[Keys.YTDLP_ALLOW_PLAYLIST] ?: false,
            ytdlpFilenameTemplate = prefs[Keys.YTDLP_FILENAME_TEMPLATE] ?: "TITLE",
            ytdlpCustomFilenameTemplate = prefs[Keys.YTDLP_CUSTOM_FILENAME_TEMPLATE] ?: "%(title).80B.%(ext)s",
            ytdlpYoutubeClient = prefs[Keys.YTDLP_YOUTUBE_CLIENT] ?: "AUTO",
            ytdlpConcurrentFragments = prefs[Keys.YTDLP_CONCURRENT_FRAGMENTS] ?: 4,
            ytdlpNoCheckCerts = prefs[Keys.YTDLP_NO_CHECK_CERTS] ?: false,
            ytdlpWriteSubs = prefs[Keys.YTDLP_WRITE_SUBS] ?: false,
            ytdlpRateLimit = prefs[Keys.YTDLP_RATE_LIMIT].orEmpty(),
            ytdlpExtraExtractorArgs = prefs[Keys.YTDLP_EXTRA_EXTRACTOR_ARGS].orEmpty(),
            ytdlpReferer = prefs[Keys.YTDLP_REFERER].orEmpty(),
            ytdlpCommandTemplatesJson = prefs[Keys.YTDLP_COMMAND_TEMPLATES].orEmpty(),
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

    suspend fun setYtdlpUseCookies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_USE_COOKIES] = enabled }
    }

    suspend fun setYtdlpAudioPreference(prefName: String) {
        context.dataStore.edit { it[Keys.YTDLP_AUDIO_PREF] = prefName }
    }

    suspend fun setYtdlpForceIpv4(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_FORCE_IPV4] = enabled }
    }

    suspend fun setYtdlpGeoBypass(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_GEO_BYPASS] = enabled }
    }

    suspend fun setYtdlpProxyUrl(proxy: String) {
        context.dataStore.edit { it[Keys.YTDLP_PROXY_URL] = proxy }
    }

    suspend fun setYtdlpUserAgent(ua: String) {
        context.dataStore.edit { it[Keys.YTDLP_USER_AGENT] = ua }
    }

    suspend fun setYtdlpAllowPlaylist(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_ALLOW_PLAYLIST] = enabled }
    }

    suspend fun setYtdlpFilenameTemplate(templateName: String) {
        context.dataStore.edit { it[Keys.YTDLP_FILENAME_TEMPLATE] = templateName }
    }

    suspend fun setYtdlpCustomFilenameTemplate(pattern: String) {
        context.dataStore.edit { it[Keys.YTDLP_CUSTOM_FILENAME_TEMPLATE] = pattern }
    }

    suspend fun setYtdlpYoutubeClient(clientName: String) {
        context.dataStore.edit { it[Keys.YTDLP_YOUTUBE_CLIENT] = clientName }
    }

    suspend fun setYtdlpConcurrentFragments(count: Int) {
        context.dataStore.edit { it[Keys.YTDLP_CONCURRENT_FRAGMENTS] = count }
    }

    suspend fun setYtdlpNoCheckCerts(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_NO_CHECK_CERTS] = enabled }
    }

    suspend fun setYtdlpWriteSubs(enabled: Boolean) {
        context.dataStore.edit { it[Keys.YTDLP_WRITE_SUBS] = enabled }
    }

    suspend fun setYtdlpRateLimit(limit: String) {
        context.dataStore.edit { it[Keys.YTDLP_RATE_LIMIT] = limit }
    }

    suspend fun setYtdlpExtraExtractorArgs(args: String) {
        context.dataStore.edit { it[Keys.YTDLP_EXTRA_EXTRACTOR_ARGS] = args }
    }

    suspend fun setYtdlpReferer(referer: String) {
        context.dataStore.edit { it[Keys.YTDLP_REFERER] = referer }
    }

    suspend fun setYtdlpCommandTemplatesJson(json: String) {
        context.dataStore.edit { it[Keys.YTDLP_COMMAND_TEMPLATES] = json }
    }

    suspend fun setYtdlpUpdateChannel(channelName: String) {
        context.dataStore.edit { it[Keys.YTDLP_UPDATE_CHANNEL] = channelName }
    }

    suspend fun setYtdlpCustomArgs(args: String) {
        context.dataStore.edit { it[Keys.YTDLP_CUSTOM_ARGS] = args }
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
