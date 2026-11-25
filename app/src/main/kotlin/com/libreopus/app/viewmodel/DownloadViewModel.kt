// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.PreferencesRepository
import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.CommandTemplateCodec
import com.libreopus.app.downloader.CommandTemplateEntry
import com.libreopus.app.downloader.CookieFileStore
import com.libreopus.app.downloader.CookieSummary
import com.libreopus.app.downloader.DownloadExecutionOptions
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.DownloadedArtifact
import com.libreopus.app.downloader.DownloadedMediaItem
import com.libreopus.app.downloader.FilenameTemplate
import com.libreopus.app.downloader.MediaTrackPreview
import com.libreopus.app.downloader.PlatformDownloader
import com.libreopus.app.downloader.PresetCommandBuilder
import com.libreopus.app.downloader.YoutubeClientMode
import com.libreopus.app.downloader.YtDlpClassifiedException
import com.libreopus.app.downloader.YtDlpEngineStatus
import com.libreopus.app.downloader.YtDlpErrorClassifier
import com.libreopus.app.downloader.YtDlpErrorDiagnostic
import com.libreopus.app.downloader.YtDlpUpdateChannel
import com.libreopus.app.downloader.YtDlpUpdateOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedMediaEntry(
    val item: DownloadedMediaItem,
    val savedPath: String?,
)

data class DownloadUiState(
    val url: String = "",
    val preset: DownloadPreset = DownloadPreset.BEST_AUDIO,
    val customArgs: String = "-f bestaudio --no-playlist",
    val audioPreference: AudioFormatPreference = AudioFormatPreference.AUTO_BEST,
    val selectedFormatId: String? = null,
    val useCookies: Boolean = true,
    val cookieSummary: CookieSummary? = null,
    val cookieImportError: String? = null,
    val allowPlaylist: Boolean = false,
    val playlistItems: String = "",
    val filenameTemplate: FilenameTemplate = FilenameTemplate.TITLE,
    val customOutputTemplate: String = "%(title).80B.%(ext)s",
    val youtubeClient: YoutubeClientMode = YoutubeClientMode.AUTO,
    val extraExtractorArgs: String = "",
    val concurrentFragments: Int = 4,
    val rateLimit: String = "",
    val noCheckCertificates: Boolean = false,
    val writeSubtitles: Boolean = false,
    val referer: String = "",
    val forceIpv4: Boolean = false,
    val geoBypass: Boolean = true,
    val proxyUrl: String = "",
    val userAgent: String = "",
    val commandTemplates: List<CommandTemplateEntry> = CommandTemplateCodec.builtInTemplates,
    val updateChannel: YtDlpUpdateChannel = YtDlpUpdateChannel.STABLE,
    val engineStatus: YtDlpEngineStatus = YtDlpEngineStatus(available = false, initialized = false),
    val isUpdatingEngine: Boolean = false,
    val engineUpdateOutcome: YtDlpUpdateOutcome? = null,
    val engineUpdateError: String? = null,
    val isFetchingPreview: Boolean = false,
    val preview: MediaTrackPreview? = null,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val logs: List<String> = emptyList(),
    val result: DownloadedArtifact? = null,
    val savedAudioPath: String? = null,
    val savedImagePath: String? = null,
    val savedMediaEntries: List<SavedMediaEntry> = emptyList(),
    val savedSubtitlePaths: List<String> = emptyList(),
    val errorDiagnostic: YtDlpErrorDiagnostic? = null,
) {
    val activeCookiePathOrNull: Boolean
        get() = useCookies && cookieSummary != null

    fun toExecutionOptions(cookieFile: File?): DownloadExecutionOptions = DownloadExecutionOptions(
        audioPreference = audioPreference,
        selectedFormatId = selectedFormatId,
        cookieFilePath = if (useCookies) cookieFile?.absolutePath else null,
        forceIpv4 = forceIpv4,
        geoBypass = geoBypass,
        allowPlaylist = allowPlaylist,
        playlistItems = playlistItems,
        filenameTemplate = filenameTemplate,
        customOutputTemplate = customOutputTemplate,
        youtubeClient = youtubeClient,
        extraExtractorArgs = extraExtractorArgs,
        concurrentFragments = concurrentFragments,
        rateLimit = rateLimit,
        noCheckCertificates = noCheckCertificates,
        writeSubtitles = writeSubtitles,
        referer = referer,
        userAgent = userAgent,
        proxyUrl = proxyUrl,
    )
}

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val downloader = PlatformDownloader.create()
    private val prefsRepo = PreferencesRepository(application)
    private var downloadJob: Job? = null
    private var previewJob: Job? = null
    private var activeWorkDir: File? = null

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = runCatching { prefsRepo.preferences.first() }.getOrNull()
            val ctx = getApplication<Application>()
            val cookies = withContext(Dispatchers.IO) { CookieFileStore.loadSummary(ctx) }
            val engineStatus = runCatching { downloader.getEngineStatus(ctx) }
                .getOrDefault(YtDlpEngineStatus(available = downloader.isAvailable, initialized = false))

            val savedTemplates = prefs?.ytdlpCommandTemplatesJson
                ?.let { CommandTemplateCodec.decode(it) }
                .orEmpty()

            _state.update { st ->
                st.copy(
                    useCookies = prefs?.ytdlpUseCookies ?: st.useCookies,
                    audioPreference = runCatching {
                        AudioFormatPreference.valueOf(prefs?.ytdlpAudioPreference ?: "")
                    }.getOrDefault(st.audioPreference),
                    allowPlaylist = prefs?.ytdlpAllowPlaylist ?: st.allowPlaylist,
                    filenameTemplate = runCatching {
                        FilenameTemplate.valueOf(prefs?.ytdlpFilenameTemplate ?: "")
                    }.getOrDefault(st.filenameTemplate),
                    customOutputTemplate = prefs?.ytdlpCustomFilenameTemplate ?: st.customOutputTemplate,
                    youtubeClient = runCatching {
                        YoutubeClientMode.valueOf(prefs?.ytdlpYoutubeClient ?: "")
                    }.getOrDefault(st.youtubeClient),
                    concurrentFragments = prefs?.ytdlpConcurrentFragments ?: st.concurrentFragments,
                    noCheckCertificates = prefs?.ytdlpNoCheckCerts ?: st.noCheckCertificates,
                    writeSubtitles = prefs?.ytdlpWriteSubs ?: st.writeSubtitles,
                    rateLimit = prefs?.ytdlpRateLimit ?: st.rateLimit,
                    extraExtractorArgs = prefs?.ytdlpExtraExtractorArgs ?: st.extraExtractorArgs,
                    referer = prefs?.ytdlpReferer ?: st.referer,
                    forceIpv4 = prefs?.ytdlpForceIpv4 ?: st.forceIpv4,
                    geoBypass = prefs?.ytdlpGeoBypass ?: st.geoBypass,
                    proxyUrl = prefs?.ytdlpProxyUrl ?: st.proxyUrl,
                    userAgent = prefs?.ytdlpUserAgent ?: st.userAgent,
                    updateChannel = runCatching {
                        YtDlpUpdateChannel.valueOf(prefs?.ytdlpUpdateChannel ?: "")
                    }.getOrDefault(st.updateChannel),
                    customArgs = prefs?.ytdlpCustomArgs ?: st.customArgs,
                    commandTemplates = CommandTemplateCodec.builtInTemplates + savedTemplates,
                    cookieSummary = cookies,
                    engineStatus = engineStatus,
                )
            }
        }
    }

    fun setUrl(url: String) {
        _state.update { st ->
            val urlChanged = url.trim() != st.url.trim()
            st.copy(
                url = url,
                errorDiagnostic = null,
                preview = if (urlChanged) null else st.preview,
                selectedFormatId = if (urlChanged) null else st.selectedFormatId,
            )
        }
    }

    fun selectPreset(preset: DownloadPreset) {
        _state.update { it.copy(preset = preset) }
    }

    fun selectFormatId(formatId: String?) {
        _state.update { it.copy(selectedFormatId = formatId?.takeIf { f -> f.isNotBlank() }) }
    }

    fun setCustomArgs(args: String) {
        _state.update { it.copy(customArgs = args) }
        viewModelScope.launch { prefsRepo.setYtdlpCustomArgs(args) }
    }

    fun appendCustomFlag(flag: String) {
        val current = _state.value.customArgs.trim()
        val updated = if (current.contains(flag)) current else listOf(current, flag).filter { it.isNotBlank() }.joinToString(" ")
        setCustomArgs(updated)
    }

    fun promotePresetToCustomScript() {
        val st = _state.value
        val exported = PresetCommandBuilder.exportAsCustomCommand(
            preset = st.preset,
            customArgs = st.customArgs,
            execOptions = st.toExecutionOptions(null),
        )
        _state.update { it.copy(preset = DownloadPreset.CUSTOM_SCRIPT, customArgs = exported) }
        viewModelScope.launch { prefsRepo.setYtdlpCustomArgs(exported) }
    }

    fun applyCommandTemplate(entry: CommandTemplateEntry) {
        _state.update { it.copy(preset = DownloadPreset.CUSTOM_SCRIPT, customArgs = entry.args) }
        viewModelScope.launch { prefsRepo.setYtdlpCustomArgs(entry.args) }
    }

    fun saveCommandTemplate(name: String, args: String = _state.value.customArgs) {
        val cleanName = name.trim()
        val cleanArgs = args.trim()
        if (cleanName.isBlank() || cleanArgs.isBlank()) return
        val newEntry = CommandTemplateEntry(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            args = cleanArgs,
            builtIn = false,
        )
        val userTemplates = _state.value.commandTemplates.filterNot { it.builtIn } + newEntry
        _state.update { it.copy(commandTemplates = CommandTemplateCodec.builtInTemplates + userTemplates) }
        viewModelScope.launch {
            prefsRepo.setYtdlpCommandTemplatesJson(CommandTemplateCodec.encode(userTemplates))
        }
    }

    fun deleteCommandTemplate(id: String) {
        val userTemplates = _state.value.commandTemplates.filter { !it.builtIn && it.id != id }
        _state.update { it.copy(commandTemplates = CommandTemplateCodec.builtInTemplates + userTemplates) }
        viewModelScope.launch {
            prefsRepo.setYtdlpCommandTemplatesJson(CommandTemplateCodec.encode(userTemplates))
        }
    }

    fun setAudioPreference(pref: AudioFormatPreference) {
        _state.update { it.copy(audioPreference = pref, selectedFormatId = null) }
        viewModelScope.launch { prefsRepo.setYtdlpAudioPreference(pref.name) }
    }

    fun setUseCookies(enabled: Boolean) {
        _state.update { it.copy(useCookies = enabled, errorDiagnostic = null) }
        viewModelScope.launch { prefsRepo.setYtdlpUseCookies(enabled) }
    }

    fun setAllowPlaylist(enabled: Boolean) {
        _state.update { it.copy(allowPlaylist = enabled, preview = null) }
        viewModelScope.launch { prefsRepo.setYtdlpAllowPlaylist(enabled) }
    }

    fun setPlaylistItems(items: String) {
        _state.update { it.copy(playlistItems = items) }
    }

    fun setFilenameTemplate(template: FilenameTemplate) {
        _state.update { it.copy(filenameTemplate = template) }
        viewModelScope.launch { prefsRepo.setYtdlpFilenameTemplate(template.name) }
    }

    fun setCustomOutputTemplate(pattern: String) {
        _state.update { it.copy(customOutputTemplate = pattern) }
        viewModelScope.launch { prefsRepo.setYtdlpCustomFilenameTemplate(pattern) }
    }

    fun setYoutubeClient(client: YoutubeClientMode) {
        _state.update { it.copy(youtubeClient = client, errorDiagnostic = null) }
        viewModelScope.launch { prefsRepo.setYtdlpYoutubeClient(client.name) }
    }

    fun setConcurrentFragments(count: Int) {
        val clamped = count.coerceIn(1, 8)
        _state.update { it.copy(concurrentFragments = clamped) }
        viewModelScope.launch { prefsRepo.setYtdlpConcurrentFragments(clamped) }
    }

    fun setNoCheckCertificates(enabled: Boolean) {
        _state.update { it.copy(noCheckCertificates = enabled) }
        viewModelScope.launch { prefsRepo.setYtdlpNoCheckCerts(enabled) }
    }

    fun setWriteSubtitles(enabled: Boolean) {
        _state.update { it.copy(writeSubtitles = enabled) }
        viewModelScope.launch { prefsRepo.setYtdlpWriteSubs(enabled) }
    }

    fun setRateLimit(limit: String) {
        _state.update { it.copy(rateLimit = limit) }
        viewModelScope.launch { prefsRepo.setYtdlpRateLimit(limit) }
    }

    fun setExtraExtractorArgs(args: String) {
        _state.update { it.copy(extraExtractorArgs = args) }
        viewModelScope.launch { prefsRepo.setYtdlpExtraExtractorArgs(args) }
    }

    fun setReferer(referer: String) {
        _state.update { it.copy(referer = referer) }
        viewModelScope.launch { prefsRepo.setYtdlpReferer(referer) }
    }

    fun setForceIpv4(enabled: Boolean) {
        _state.update { it.copy(forceIpv4 = enabled, errorDiagnostic = null) }
        viewModelScope.launch { prefsRepo.setYtdlpForceIpv4(enabled) }
    }

    fun setGeoBypass(enabled: Boolean) {
        _state.update { it.copy(geoBypass = enabled) }
        viewModelScope.launch { prefsRepo.setYtdlpGeoBypass(enabled) }
    }

    fun setProxyUrl(proxy: String) {
        _state.update { it.copy(proxyUrl = proxy) }
        viewModelScope.launch { prefsRepo.setYtdlpProxyUrl(proxy) }
    }

    fun setUserAgent(ua: String) {
        _state.update { it.copy(userAgent = ua) }
        viewModelScope.launch { prefsRepo.setYtdlpUserAgent(ua) }
    }

    fun setUpdateChannel(channel: YtDlpUpdateChannel) {
        _state.update { it.copy(updateChannel = channel, engineUpdateOutcome = null, engineUpdateError = null) }
        viewModelScope.launch { prefsRepo.setYtdlpUpdateChannel(channel.name) }
    }

    fun clearCookieError() {
        _state.update { it.copy(cookieImportError = null) }
    }

    suspend fun importCookiesFromText(rawText: String): Boolean {
        val ctx = getApplication<Application>()
        return try {
            val summary = withContext(Dispatchers.IO) {
                CookieFileStore.saveCookies(ctx, rawText)
            }
            prefsRepo.setYtdlpUseCookies(true)
            _state.update {
                it.copy(
                    cookieSummary = summary,
                    useCookies = true,
                    cookieImportError = null,
                    errorDiagnostic = null,
                )
            }
            true
        } catch (t: Throwable) {
            _state.update {
                it.copy(cookieImportError = t.localizedMessage ?: "Invalid cookie format")
            }
            false
        }
    }

    fun importCookiesFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val readRes = withContext(Dispatchers.IO) {
                runCatching {
                    val maxBytes = 2 * 1024 * 1024
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= maxBytes) { "Cookie file is too large (max 2 MB)" }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("Cannot open selected cookie file")
                }
            }
            readRes.fold(
                onSuccess = { text ->
                    val ok = importCookiesFromText(text)
                    onComplete(ok)
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(cookieImportError = err.localizedMessage ?: "Cannot read cookie file")
                    }
                    onComplete(false)
                },
            )
        }
    }

    fun clearCookies() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CookieFileStore.clearCookies(ctx) }
            _state.update { it.copy(cookieSummary = null, cookieImportError = null) }
        }
    }

    fun updateEngine(channel: YtDlpUpdateChannel = _state.value.updateChannel) {
        if (_state.value.isUpdatingEngine || !downloader.isAvailable) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isUpdatingEngine = true,
                    engineUpdateOutcome = null,
                    engineUpdateError = null,
                )
            }
            try {
                val outcome = downloader.updateEngine(ctx, channel)
                val status = downloader.getEngineStatus(ctx)
                _state.update {
                    it.copy(
                        isUpdatingEngine = false,
                        engineUpdateOutcome = outcome,
                        engineStatus = status,
                    )
                }
            } catch (ce: CancellationException) {
                _state.update { it.copy(isUpdatingEngine = false) }
                throw ce
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isUpdatingEngine = false,
                        engineUpdateError = t.localizedMessage ?: "Update failed",
                    )
                }
            }
        }
    }

    fun fetchPreview() {
        val st = _state.value
        val trimmedUrl = st.url.trim()
        if (trimmedUrl.isBlank() || st.isRunning || st.isFetchingPreview) return

        val ctx = getApplication<Application>()
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isFetchingPreview = true,
                    preview = null,
                    selectedFormatId = null,
                    errorDiagnostic = null,
                )
            }
            try {
                val cookieFile = withContext(Dispatchers.IO) {
                    if (st.useCookies) CookieFileStore.getCookieFileIfPresent(ctx) else null
                }
                val execOpts = st.toExecutionOptions(cookieFile)
                val info = downloader.fetchPreview(ctx, trimmedUrl, execOpts)
                val status = runCatching { downloader.getEngineStatus(ctx) }.getOrNull()
                _state.update {
                    it.copy(
                        isFetchingPreview = false,
                        preview = info,
                        engineStatus = status ?: it.engineStatus,
                    )
                }
            } catch (ce: CancellationException) {
                _state.update { it.copy(isFetchingPreview = false) }
                throw ce
            } catch (t: Throwable) {
                val diag = (t as? YtDlpClassifiedException)?.diagnostic
                    ?: YtDlpErrorClassifier.classify(t.localizedMessage ?: "Preview failed")
                _state.update {
                    it.copy(
                        isFetchingPreview = false,
                        errorDiagnostic = diag,
                    )
                }
            }
        }
    }

    fun startDownload() {
        val current = _state.value
        val trimmedUrl = current.url.trim()
        if (trimmedUrl.isBlank() || current.isRunning) return

        previewJob?.cancel()
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "ytdlp/${System.currentTimeMillis()}").apply { mkdirs() }
        activeWorkDir?.deleteRecursively()
        activeWorkDir = dir

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    isFetchingPreview = false,
                    progress = 0f,
                    etaSeconds = -1L,
                    logs = emptyList(),
                    result = null,
                    savedAudioPath = null,
                    savedImagePath = null,
                    savedMediaEntries = emptyList(),
                    savedSubtitlePaths = emptyList(),
                    errorDiagnostic = null,
                )
            }
            try {
                val cookieFile = withContext(Dispatchers.IO) {
                    if (current.useCookies) CookieFileStore.getCookieFileIfPresent(ctx) else null
                }
                val execOpts = current.toExecutionOptions(cookieFile)
                val artifact = downloader.execute(
                    context = ctx,
                    url = trimmedUrl,
                    preset = current.preset,
                    customArgs = current.customArgs,
                    workDir = dir,
                    options = execOpts,
                ) { pct, eta, line ->
                    _state.update { st ->
                        val nextLogs = if (line.isNotBlank()) (st.logs + line).takeLast(80) else st.logs
                        st.copy(
                            progress = (pct / 100f).coerceIn(0f, 1f),
                            etaSeconds = eta,
                            logs = nextLogs,
                        )
                    }
                }

                val savedEntries = withContext(Dispatchers.IO) {
                    artifact.mediaItems.map { media ->
                        val path = MediaStorageHelper.saveDownloadedMediaToStorage(
                            context = ctx,
                            fileName = media.displayName,
                            mimeType = media.mimeType,
                            sourceFile = media.file,
                            isVideo = media.isVideo,
                        )
                        SavedMediaEntry(item = media, savedPath = path)
                    }
                }

                val savedImage = artifact.imageFile?.let { img ->
                    val mime = artifact.imageMimeType ?: "image/jpeg"
                    withContext(Dispatchers.IO) {
                        MediaStorageHelper.saveImageToPictures(ctx, img.name, mime, img)
                    }
                }

                val savedSubs = withContext(Dispatchers.IO) {
                    artifact.subtitleFiles.mapNotNull { sub ->
                        MediaStorageHelper.saveFileToDownloads(ctx, sub.name, "text/plain", sub)
                    }
                }

                val status = runCatching { downloader.getEngineStatus(ctx) }.getOrNull()

                _state.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        result = artifact,
                        savedAudioPath = savedEntries.firstOrNull()?.savedPath,
                        savedImagePath = savedImage,
                        savedMediaEntries = savedEntries,
                        savedSubtitlePaths = savedSubs,
                        engineStatus = status ?: it.engineStatus,
                    )
                }
            } catch (ce: CancellationException) {
                _state.update { it.copy(isRunning = false) }
                throw ce
            } catch (t: Throwable) {
                val diag = (t as? YtDlpClassifiedException)?.diagnostic
                    ?: YtDlpErrorClassifier.classify(t.localizedMessage ?: "Download failed")
                _state.update { st ->
                    val nextLogs = if (diag.rawDetails.isNotBlank()) {
                        (st.logs + diag.rawDetails.lines().filter { it.isNotBlank() }).takeLast(80)
                    } else {
                        st.logs
                    }
                    st.copy(
                        isRunning = false,
                        logs = nextLogs,
                        errorDiagnostic = diag,
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloader.cancel()
        previewJob?.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(isRunning = false, isFetchingPreview = false) }
    }

    override fun onCleared() {
        downloader.cancel()
        activeWorkDir?.deleteRecursively()
    }
}
