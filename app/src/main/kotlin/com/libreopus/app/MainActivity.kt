// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import android.Manifest
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.libreopus.app.data.AppLanguage
import com.libreopus.app.data.Formatters
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.PreferencesRepository
import com.libreopus.app.data.UserPreferences
import com.libreopus.app.downloader.PlatformDownloadFeature
import com.libreopus.app.downloader.PlatformDownloadHost
import com.libreopus.app.ui.screens.ConverterScreen
import com.libreopus.app.ui.screens.EditorScreen
import com.libreopus.app.ui.screens.HomeScreen
import com.libreopus.app.ui.screens.SettingsScreen
import com.libreopus.app.ui.sheets.RecorderSheet
import com.libreopus.app.ui.theme.LibreOpusTheme
import com.libreopus.app.viewmodel.ConverterViewModel
import com.libreopus.app.viewmodel.EditorEvent
import com.libreopus.app.viewmodel.EditorViewModel
import com.libreopus.audio.api.ExportConfig
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TopTab { STUDIO, EDITOR, DOWNLOAD, CONVERTER, SETTINGS }

class MainActivity : ComponentActivity() {

    private var pendingIncomingUri by mutableStateOf<Uri?>(null)
    private var pendingIncomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntentPayload(intent)

        setContent {
            val context = LocalContext.current
            val prefsRepo = remember { PreferencesRepository(context) }
            val prefs by prefsRepo.preferences.collectAsStateWithLifecycle(initialValue = UserPreferences())
            val editorVm: EditorViewModel = viewModel()
            val converterVm: ConverterViewModel = viewModel()
            val downloadHost = PlatformDownloadFeature.rememberHost()

            LibreOpusTheme(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.dynamicColor,
                amoledBlack = prefs.amoledBlack,
            ) {
                LibreOpusAppContent(
                    prefs = prefs,
                    prefsRepo = prefsRepo,
                    editorVm = editorVm,
                    converterVm = converterVm,
                    downloadHost = downloadHost,
                    incomingUri = pendingIncomingUri,
                    incomingUrl = pendingIncomingUrl,
                    onConsumeIncomingUri = { pendingIncomingUri = null },
                    onConsumeIncomingUrl = { pendingIncomingUrl = null },
                    onApplyLanguage = { lang -> applyAppLanguage(lang) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentPayload(intent)
    }

    private fun handleIntentPayload(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain" && BuildConfig.HAS_YTDLP) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val match = Regex("https?://\\S+").find(sharedText)?.value
            if (!match.isNullOrBlank()) {
                pendingIncomingUrl = match
                return
            }
        }
        pendingIncomingUri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
    }

    private fun applyAppLanguage(lang: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = getSystemService(LocaleManager::class.java)
            lm.applicationLocales = when (lang) {
                AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
                AppLanguage.EN -> LocaleList.forLanguageTags("en")
                AppLanguage.TR -> LocaleList.forLanguageTags("tr")
            }
        }
    }
}

@Composable
private fun LibreOpusAppContent(
    prefs: UserPreferences,
    prefsRepo: PreferencesRepository,
    editorVm: EditorViewModel,
    converterVm: ConverterViewModel,
    downloadHost: PlatformDownloadHost,
    incomingUri: Uri?,
    incomingUrl: String?,
    onConsumeIncomingUri: () -> Unit,
    onConsumeIncomingUrl: () -> Unit,
    onApplyLanguage: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val editorState by editorVm.state.collectAsStateWithLifecycle()
    val converterState by converterVm.state.collectAsStateWithLifecycle()

    var currentTab by remember { mutableStateOf(TopTab.STUDIO) }
    var pendingCoverCallback by remember { mutableStateOf<((ByteArray, String) -> Unit)?>(null) }
    var pendingSaveAsConfig by remember { mutableStateOf<Pair<ExportConfig, Boolean>?>(null) }
    var pendingMixTarget by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var showRecorderSheet by remember { mutableStateOf(false) }
    var cacheSizeBytes by remember { mutableLongStateOf(0L) }

    val writeSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        editorVm.completePendingRingtoneAssignIfReady()
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentTab = TopTab.EDITOR
            editorVm.openUri(uri)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentTab = TopTab.EDITOR
            editorVm.openUri(uri)
        }
    }

    val appendPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) editorVm.appendAudioUri(uri)
    }

    val mixPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = MediaStorageHelper.buildAudioSource(context, uri).displayName
            pendingMixTarget = uri to name
        }
    }

    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) converterVm.addFiles(uris)
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pendingCoverCallback
        pendingCoverCallback = null
        if (uri != null && cb != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
            if (bytes != null && bytes.isNotEmpty()) {
                cb(bytes, mime)
            }
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val req = pendingSaveAsConfig
        pendingSaveAsConfig = null
        if (uri != null && req != null) {
            editorVm.exportToSafUri(uri, req.first, req.second)
        }
    }

    val micDeniedMsg = stringResource(R.string.recorder_permission_denied)
    val recordAudioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showRecorderSheet = true
        } else {
            scope.launch { snackbarHostState.showSnackbar(micDeniedMsg) }
        }
    }

    LaunchedEffect(currentTab, editorState.hasSession) {
        cacheSizeBytes = withContext(Dispatchers.IO) {
            MediaStorageHelper.computeTempCacheSizeBytes(context.cacheDir)
        }
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            currentTab = TopTab.EDITOR
            editorVm.openUri(incomingUri)
            onConsumeIncomingUri()
        }
    }

    downloadHost.HandleIncomingUrl(
        incomingUrl = incomingUrl,
        onNavigateToDownload = { currentTab = TopTab.DOWNLOAD },
        onConsumeIncomingUrl = onConsumeIncomingUrl,
    )

    val shareTitle = stringResource(R.string.export_share)
    val missingFileMsg = stringResource(R.string.download_history_file_missing)
    val grantWritePermMsg = stringResource(R.string.export_ringtone_grant_perm)

    LaunchedEffect(shareTitle, grantWritePermMsg) {
        editorVm.events.collect { event ->
            when (event) {
                is EditorEvent.Message -> {
                    snackbarHostState.showSnackbar(event.text)
                }
                is EditorEvent.ShareReady -> {
                    val shareIntent = MediaStorageHelper.createShareIntent(context, event.file, event.mimeType)
                    context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                }
                is EditorEvent.RequestWriteSettingsPermission -> {
                    scope.launch {
                        snackbarHostState.showSnackbar("${event.savedDisplayPath} • $grantWritePermMsg")
                    }
                    runCatching {
                        writeSettingsLauncher.launch(MediaStorageHelper.buildManageWriteSettingsIntent(context))
                    }.onFailure {
                        runCatching { context.startActivity(MediaStorageHelper.buildSoundSettingsIntent()) }
                    }
                }
                is EditorEvent.OpenRingtonePickerFallback -> {
                    runCatching {
                        context.startActivity(
                            MediaStorageHelper.buildRingtonePickerIntent(
                                targetType = event.targetType,
                                title = event.title,
                                existingUri = event.uri,
                            ),
                        )
                    }.onFailure {
                        runCatching { context.startActivity(MediaStorageHelper.buildSoundSettingsIntent()) }
                    }
                }
            }
        }
    }

    if (currentTab == TopTab.EDITOR && (editorState.hasSession || editorState.busyLabel != null)) {
        EditorScreen(
            state = editorState,
            viewModel = editorVm,
            snackbarHostState = snackbarHostState,
            defaultExportFormat = prefs.defaultExportFormat,
            defaultBitrateKbps = prefs.defaultBitrateKbps,
            onBack = { currentTab = TopTab.STUDIO },
            onPickAppendFile = { appendPicker.launch(arrayOf("audio/*", "application/ogg", "video/*")) },
            onPickMixFile = { mixPicker.launch(arrayOf("audio/*", "application/ogg", "video/*")) },
            onPickCoverImage = { cb ->
                pendingCoverCallback = cb
                coverPicker.launch("image/*")
            },
            onExportSaveAs = { cfg, selectionOnly ->
                pendingSaveAsConfig = cfg to selectionOnly
                val base = (editorState.info?.displayName ?: "libreopus").substringBeforeLast('.')
                val suffix = if (selectionOnly) "_clip" else "_edited"
                saveAsLauncher.launch("${base}${suffix}.${cfg.format.extension}")
            },
            pendingMixOverlayName = pendingMixTarget?.second,
            onConfirmMixOverlay = { offsetMs, baseGainDb, overlayGainDb ->
                val target = pendingMixTarget
                pendingMixTarget = null
                if (target != null) {
                    editorVm.mixOverlayUri(target.first, offsetMs, baseGainDb, overlayGainDb)
                }
            },
            onDismissMixOverlay = { pendingMixTarget = null },
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == TopTab.STUDIO,
                        onClick = { currentTab = TopTab.STUDIO },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                        label = { NavLabel(stringResource(R.string.nav_studio)) },
                    )
                    if (editorState.hasSession) {
                        NavigationBarItem(
                            selected = currentTab == TopTab.EDITOR,
                            onClick = { currentTab = TopTab.EDITOR },
                            icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                            label = { NavLabel(stringResource(R.string.nav_editor)) },
                        )
                    }
                    if (BuildConfig.HAS_YTDLP) {
                        NavigationBarItem(
                            selected = currentTab == TopTab.DOWNLOAD,
                            onClick = { currentTab = TopTab.DOWNLOAD },
                            icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            label = { NavLabel(stringResource(R.string.nav_download)) },
                        )
                    }
                    NavigationBarItem(
                        selected = currentTab == TopTab.CONVERTER,
                        onClick = { currentTab = TopTab.CONVERTER },
                        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                        label = { NavLabel(stringResource(R.string.nav_converter)) },
                    )
                    NavigationBarItem(
                        selected = currentTab == TopTab.SETTINGS,
                        onClick = { currentTab = TopTab.SETTINGS },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { NavLabel(stringResource(R.string.nav_settings)) },
                    )
                }
            },
        ) { innerPadding ->
            when (currentTab) {
                TopTab.STUDIO, TopTab.EDITOR -> HomeScreen(
                    recentFiles = prefs.recentFiles,
                    hasDownloader = BuildConfig.HAS_YTDLP,
                    onPickAudio = { audioPicker.launch(arrayOf("audio/*", "application/ogg")) },
                    onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onOpenRecent = { uri ->
                        currentTab = TopTab.EDITOR
                        editorVm.openUri(uri)
                    },
                    onClearRecents = { scope.launch { prefsRepo.clearRecents() } },
                    onOpenConverter = { currentTab = TopTab.CONVERTER },
                    onOpenDownloader = { currentTab = TopTab.DOWNLOAD },
                    onRecordAudio = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            showRecorderSheet = true
                        } else {
                            recordAudioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
                TopTab.DOWNLOAD -> downloadHost.RenderDownloadTab(
                    hasActiveEditorSession = editorState.hasSession,
                    onOpenLocalInEditor = { audioFile, displayName, coverFile, coverMime, metadata, chapters ->
                        val coverBytes = coverFile?.takeIf { it.exists() }?.readBytes()
                        currentTab = TopTab.EDITOR
                        editorVm.openLocalFile(audioFile, displayName, coverBytes, coverMime, metadata, chapters)
                    },
                    onUseAsCover = { imgFile, mime ->
                        val bytes = imgFile.takeIf { it.exists() }?.readBytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            editorVm.applyCoverArtBytes(bytes, mime)
                            currentTab = TopTab.EDITOR
                        }
                    },
                    onShareFile = { file, mime ->
                        val shareIntent = MediaStorageHelper.createShareIntent(context, file, mime)
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    },
                    onOpenHistoryUri = { uriStr, chapters ->
                        val uri = uriStr.toUri()
                        val readable = runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } != null
                        }.getOrDefault(false)
                        if (readable) {
                            currentTab = TopTab.EDITOR
                            editorVm.openUri(uri, chapters = chapters)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(missingFileMsg)
                            }
                        }
                    },
                    onShareHistoryUri = { uriStr, mime ->
                        val uri = uriStr.toUri()
                        val readable = runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } != null
                        }.getOrDefault(false)
                        if (readable) {
                            val shareIntent = MediaStorageHelper.createShareUriIntent(context, uri, mime)
                            context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(missingFileMsg)
                            }
                        }
                    },
                    onShowMessage = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
                TopTab.CONVERTER -> ConverterScreen(
                    state = converterState,
                    viewModel = converterVm,
                    onAddFiles = { batchPicker.launch(arrayOf("audio/*", "application/ogg", "video/*")) },
                    onOpenConvertedUri = { uri ->
                        val readable = runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } != null
                        }.getOrDefault(false)
                        if (readable) {
                            currentTab = TopTab.EDITOR
                            editorVm.openUri(uri)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(missingFileMsg)
                            }
                        }
                    },
                    onShareConvertedUri = { uri, mime ->
                        val readable = runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } != null
                        }.getOrDefault(false)
                        if (readable) {
                            val shareIntent = MediaStorageHelper.createShareUriIntent(context, uri, mime)
                            context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(missingFileMsg)
                            }
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
                TopTab.SETTINGS -> SettingsScreen(
                    prefs = prefs,
                    onThemeModeChange = { m -> scope.launch { prefsRepo.setThemeMode(m) } },
                    onDynamicColorChange = { d -> scope.launch { prefsRepo.setDynamicColor(d) } },
                    onAmoledBlackChange = { a -> scope.launch { prefsRepo.setAmoledBlack(a) } },
                    onLanguageChange = { l ->
                        scope.launch {
                            prefsRepo.setLanguage(l)
                            onApplyLanguage(l)
                        }
                    },
                    onDefaultFormatChange = { f -> scope.launch { prefsRepo.setDefaultExportFormat(f) } },
                    onDefaultBitrateChange = { b -> scope.launch { prefsRepo.setDefaultBitrate(b) } },
                    cacheSizeBytes = cacheSizeBytes,
                    onClearCache = {
                        scope.launch {
                            val keep = listOfNotNull(editorVm.activeSessionCachePath()).toSet()
                            val freed = withContext(Dispatchers.IO) {
                                val bytes = MediaStorageHelper.clearTempCache(context.cacheDir, keepPaths = keep)
                                cacheSizeBytes = MediaStorageHelper.computeTempCacheSizeBytes(context.cacheDir)
                                bytes
                            }
                            snackbarHostState.showSnackbar(
                                resources.getString(R.string.settings_cache_cleared, Formatters.formatBytes(freed)),
                            )
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                    ytDlpSettingsSlot = if (BuildConfig.HAS_YTDLP) {
                        { downloadHost.RenderYtDlpSettingsCard() }
                    } else {
                        null
                    },
                )
            }
        }
    }

    if (showRecorderSheet) {
        RecorderSheet(
            onFinished = { wavFile, displayName ->
                showRecorderSheet = false
                currentTab = TopTab.EDITOR
                editorVm.openLocalFile(wavFile, displayName)
            },
            onDismiss = { showRecorderSheet = false },
        )
    }
}

@Composable
private fun NavLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

