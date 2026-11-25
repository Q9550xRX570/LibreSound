// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.libreopus.app.R
import com.libreopus.app.data.Formatters
import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.FilenameTemplate
import com.libreopus.app.downloader.MediaFormatOption
import com.libreopus.app.downloader.PlatformCookieWebLogin
import com.libreopus.app.downloader.PresetCommandBuilder
import com.libreopus.app.downloader.YoutubeClientMode
import com.libreopus.app.downloader.YtDlpErrorDiagnostic
import com.libreopus.app.downloader.YtDlpErrorKind
import com.libreopus.app.viewmodel.DownloadUiState
import com.libreopus.app.viewmodel.DownloadViewModel
import com.libreopus.audio.api.AudioMetadata
import java.io.File
import kotlinx.coroutines.launch

private data class MediaPropertiesPayload(
    val title: String,
    val coverPath: String? = null,
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val durationSec: Int = 0,
    val formatLabel: String = "",
    val sizeBytes: Long = 0L,
    val savedPath: String = "",
    val sourceUrl: String = "",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(
    state: DownloadUiState,
    viewModel: DownloadViewModel,
    hasActiveEditorSession: Boolean,
    onOpenInEditor: (audioFile: File, displayName: String, coverFile: File?, coverMime: String?, metadata: AudioMetadata?) -> Unit,
    onUseAsCover: (imageFile: File, mimeType: String) -> Unit,
    onShareFile: (file: File, mimeType: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistoryUri: (uriString: String) -> Unit = {},
    onShareHistoryUri: (uriString: String, mimeType: String) -> Unit = { _, _ -> },
    onShowMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showCookieSheet by remember { mutableStateOf(false) }
    var showFormatSheet by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showErrorDetails by remember { mutableStateOf(false) }
    var activeProperties by remember { mutableStateOf<MediaPropertiesPayload?>(null) }
    var askedNotifPermission by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        askedNotifPermission = true
        viewModel.startDownload()
    }

    val startDownloadWithPermissionCheck = {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotifPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPrompt) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startDownload()
        }
    }

    val execOptionsPreview = remember(
        state.audioPreference,
        state.selectedFormatId,
        state.useCookies,
        state.cookieSummary,
        state.allowPlaylist,
        state.playlistItems,
        state.youtubeClient,
        state.concurrentFragments,
        state.forceIpv4,
        state.geoBypass,
        state.noCheckCertificates,
        state.writeSubtitles,
        state.proxyUrl,
    ) {
        state.toExecutionOptions(
            cookieFile = if (state.activeCookiePathOrNull) File("/cookies.txt") else null,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.download_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.download_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // URL Input (single or multi-line) + Preview & Formats + Cookies
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { viewModel.setUrl(it) },
                    label = { Text(stringResource(R.string.download_url_hint)) },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !state.isRunning,
                    trailingIcon = {
                        Row {
                            if (state.url.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setUrl("") }, enabled = !state.isRunning) {
                                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.download_clear))
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clip = clipboard.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) viewModel.setUrl(clip)
                                },
                                enabled = !state.isRunning,
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.download_paste))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.fetchPreview() },
                        enabled = state.url.isNotBlank() && !state.isRunning && !state.isFetchingPreview,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.isFetchingPreview) {
                                stringResource(R.string.download_fetching_info)
                            } else {
                                stringResource(R.string.download_fetch_info)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { showCookieSheet = true },
                        enabled = !state.isRunning,
                    ) {
                        Icon(Icons.Rounded.Cookie, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.cookieSummary != null) {
                                "${stringResource(R.string.cookies_manage)} (${state.cookieSummary.cookieCount})"
                            } else {
                                stringResource(R.string.cookies_manage)
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Preview + Format Picker / Playlist Preview Card (YTDLnis style with cover & properties)
        val preview = state.preview
        if (preview != null) {
            item {
                val thumbPath = state.previewThumbnailPath
                val thumbBmp = remember(thumbPath) {
                    thumbPath?.let { p ->
                        runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull()
                    }
                }
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(186.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            if (thumbBmp != null) {
                                Image(
                                    bitmap = thumbBmp,
                                    contentDescription = preview.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    imageVector = if (preview.isPlaylist) {
                                        Icons.AutoMirrored.Rounded.PlaylistPlay
                                    } else {
                                        Icons.Rounded.AudioFile
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .align(Alignment.Center),
                                )
                            }

                            preview.extractor?.takeIf { it.isNotBlank() }?.let { extName ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp),
                                ) {
                                    Text(
                                        text = extName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            val rightBadge = when {
                                preview.isPlaylist -> stringResource(R.string.playlist_count_badge, preview.playlistCount)
                                preview.durationSec > 0 -> Formatters.formatDurationMs(
                                    preview.durationSec * 1000L,
                                    includeMillis = false,
                                )
                                else -> ""
                            }
                            if (rightBadge.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.78f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                ) {
                                    Text(
                                        text = rightBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }

                        Text(
                            text = preview.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (preview.uploader.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = preview.uploader,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        val previewBadges = buildList {
                            preview.audioExt?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                            if (preview.approxFileSizeBytes > 0L) {
                                add("~${Formatters.formatBytes(preview.approxFileSizeBytes)}")
                            }
                            val bestRate = preview.formats.firstOrNull { it.isAudioOnly }?.let { maxOf(it.abrKbps, it.tbrKbps) } ?: 0
                            if (bestRate > 0) add("${bestRate} kbps")
                        }
                        if (previewBadges.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                previewBadges.forEach { badge ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (preview.isPlaylist && preview.playlistEntries.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                preview.playlistEntries.take(5).forEach { entry ->
                                    Text(
                                        text = "${entry.index}. ${entry.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("1-5", "1-10", "1-25", "").forEach { range ->
                                    FilterChip(
                                        selected = state.playlistItems == range,
                                        onClick = { viewModel.setPlaylistItems(range) },
                                        label = { Text(if (range.isEmpty()) "Tümü / All" else range) },
                                    )
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (preview.formats.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = { showFormatSheet = true },
                                    enabled = !state.isRunning,
                                ) {
                                    Text(stringResource(R.string.formats_pick_button, preview.formats.size))
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    activeProperties = MediaPropertiesPayload(
                                        title = preview.title,
                                        coverPath = state.previewThumbnailPath,
                                        artist = preview.uploader,
                                        durationSec = preview.durationSec,
                                        formatLabel = listOfNotNull(
                                            preview.audioExt?.uppercase(),
                                            preview.extractor,
                                        ).joinToString(" • "),
                                        sizeBytes = preview.approxFileSizeBytes,
                                        sourceUrl = preview.webpageUrl ?: state.url.trim(),
                                    )
                                },
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.download_properties_action))
                            }
                            state.selectedFormatId?.let { fid ->
                                InputChip(
                                    selected = true,
                                    onClick = { viewModel.selectFormatId(null) },
                                    label = { Text("#$fid") },
                                    trailingIcon = {
                                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Cookie Status Banner
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = if (state.activeCookiePathOrNull) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cookies_title), style = MaterialTheme.typography.labelLarge)
                        val summary = state.cookieSummary
                        val subtitle = when {
                            summary == null -> stringResource(R.string.cookies_none)
                            state.useCookies -> stringResource(
                                R.string.cookies_active_summary,
                                summary.cookieCount,
                                summary.domains.take(2).joinToString(", ").ifBlank { "cookies.txt" },
                            )
                            else -> stringResource(R.string.cookies_loaded_paused, summary.cookieCount)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.cookieSummary != null) {
                        Switch(
                            checked = state.useCookies,
                            onCheckedChange = { viewModel.setUseCookies(it) },
                            enabled = !state.isRunning,
                        )
                    } else {
                        TextButton(onClick = { showCookieSheet = true }, enabled = !state.isRunning) {
                            Text(stringResource(R.string.ytdlp_action_add_cookies))
                        }
                    }
                }
            }
        }

        // Presets
        item {
            Text(stringResource(R.string.download_presets_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetCard(
                    selected = state.preset == DownloadPreset.BEST_AUDIO,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.AudioFile,
                    title = stringResource(R.string.download_preset_best_audio),
                    subtitle = stringResource(R.string.download_preset_best_audio_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.BEST_AUDIO) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.AUDIO_AND_IMAGE,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Album,
                    title = stringResource(R.string.download_preset_audio_image),
                    subtitle = stringResource(R.string.download_preset_audio_image_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.AUDIO_AND_IMAGE) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.VIDEO_MUXED,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Movie,
                    title = stringResource(R.string.download_preset_video_muxed),
                    subtitle = stringResource(R.string.download_preset_video_muxed_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.VIDEO_MUXED) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.IMAGE_ONLY,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.download_preset_image_only),
                    subtitle = stringResource(R.string.download_preset_image_only_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.IMAGE_ONLY) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.CUSTOM_SCRIPT,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.download_preset_custom),
                    subtitle = stringResource(R.string.download_preset_custom_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.CUSTOM_SCRIPT) },
                )
            }
        }

        // Format preference or Custom Script & Templates
        item {
            if (state.preset == DownloadPreset.BEST_AUDIO || state.preset == DownloadPreset.AUDIO_AND_IMAGE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.selectedFormatId != null) {
                        AssistChip(
                            onClick = { showFormatSheet = true },
                            label = { Text(stringResource(R.string.formats_selected_badge, state.selectedFormatId)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.selectFormatId(null) },
                                    modifier = Modifier.size(18.dp),
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                        )
                    } else {
                        Text(stringResource(R.string.download_audio_format_label), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.audioPreference == AudioFormatPreference.AUTO_BEST,
                                onClick = { viewModel.setAudioPreference(AudioFormatPreference.AUTO_BEST) },
                                enabled = !state.isRunning,
                                label = { Text(stringResource(R.string.download_format_auto)) },
                            )
                            FilterChip(
                                selected = state.audioPreference == AudioFormatPreference.OPUS_WEBM,
                                onClick = { viewModel.setAudioPreference(AudioFormatPreference.OPUS_WEBM) },
                                enabled = !state.isRunning,
                                label = { Text(stringResource(R.string.download_format_opus)) },
                            )
                            FilterChip(
                                selected = state.audioPreference == AudioFormatPreference.M4A_AAC,
                                onClick = { viewModel.setAudioPreference(AudioFormatPreference.M4A_AAC) },
                                enabled = !state.isRunning,
                                label = { Text(stringResource(R.string.download_format_m4a)) },
                            )
                        }
                    }
                }
            }

            if (state.preset == DownloadPreset.CUSTOM_SCRIPT) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.download_templates_label), style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.commandTemplates.forEach { tmpl ->
                            InputChip(
                                selected = state.customArgs.trim() == tmpl.args.trim(),
                                onClick = { viewModel.applyCommandTemplate(tmpl) },
                                enabled = !state.isRunning,
                                label = { Text(tmpl.name) },
                                trailingIcon = if (!tmpl.builtIn) {
                                    {
                                        IconButton(
                                            onClick = { viewModel.deleteCommandTemplate(tmpl.id) },
                                            modifier = Modifier.size(18.dp),
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.customArgs,
                        onValueChange = { viewModel.setCustomArgs(it) },
                        label = { Text(stringResource(R.string.download_custom_args_label)) },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !state.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showSaveTemplateDialog = true },
                            enabled = !state.isRunning && state.customArgs.isNotBlank(),
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.download_save_template))
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val quickFlags = listOf(
                            "--force-ipv4",
                            "-N 4",
                            "--write-thumbnail",
                            "--write-subs",
                            "--yes-playlist",
                            "--playlist-items 1-5",
                            "--no-check-certificates",
                        )
                        quickFlags.forEach { flag ->
                            AssistChip(
                                onClick = { viewModel.appendCustomFlag(flag) },
                                enabled = !state.isRunning,
                                label = { Text(flag) },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.download_command_preview,
                            PresetCommandBuilder.formatPreview(state.preset, execOptionsPreview),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.promotePresetToCustomScript() },
                        enabled = !state.isRunning,
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.download_customize_command))
                    }
                }
            }
        }

        // Expandable YTDLnis Power, Network & Output Options
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.download_advanced_options),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(visible = showAdvanced) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .padding(bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.download_allow_playlist))
                                }
                                Switch(
                                    checked = state.allowPlaylist,
                                    onCheckedChange = { viewModel.setAllowPlaylist(it) },
                                    enabled = !state.isRunning,
                                )
                            }

                            if (state.allowPlaylist) {
                                OutlinedTextField(
                                    value = state.playlistItems,
                                    onValueChange = { viewModel.setPlaylistItems(it) },
                                    label = { Text(stringResource(R.string.download_playlist_items_label)) },
                                    singleLine = true,
                                    enabled = !state.isRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Text(stringResource(R.string.download_youtube_client_label), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                YoutubeClientMode.entries.forEach { mode ->
                                    val label = when (mode) {
                                        YoutubeClientMode.AUTO -> stringResource(R.string.download_client_auto)
                                        YoutubeClientMode.ANDROID_VR -> "Android VR"
                                        YoutubeClientMode.ANDROID -> "Android"
                                        YoutubeClientMode.IOS -> "iOS"
                                        YoutubeClientMode.WEB_SAFARI -> "Web Safari"
                                        YoutubeClientMode.WEB_EMBEDDED -> "Web Embedded"
                                        YoutubeClientMode.TV_DOWNGRADED -> "TV Downgraded"
                                        YoutubeClientMode.MWEB -> "mWeb"
                                        YoutubeClientMode.TV -> "TV"
                                    }
                                    FilterChip(
                                        selected = state.youtubeClient == mode,
                                        onClick = { viewModel.setYoutubeClient(mode) },
                                        enabled = !state.isRunning,
                                        label = { Text(label) },
                                    )
                                }
                            }

                            Text(stringResource(R.string.download_filename_template_label), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilenameTemplate.entries.forEach { tmpl ->
                                    val label = when (tmpl) {
                                        FilenameTemplate.TITLE -> stringResource(R.string.download_tmpl_title)
                                        FilenameTemplate.UPLOADER_TITLE -> stringResource(R.string.download_tmpl_uploader)
                                        FilenameTemplate.PLAYLIST_INDEX_TITLE -> stringResource(R.string.download_tmpl_playlist)
                                        FilenameTemplate.ID_ONLY -> stringResource(R.string.download_tmpl_id)
                                        FilenameTemplate.CUSTOM -> stringResource(R.string.download_tmpl_custom)
                                    }
                                    FilterChip(
                                        selected = state.filenameTemplate == tmpl,
                                        onClick = { viewModel.setFilenameTemplate(tmpl) },
                                        enabled = !state.isRunning,
                                        label = { Text(label) },
                                    )
                                }
                            }

                            if (state.filenameTemplate == FilenameTemplate.CUSTOM) {
                                OutlinedTextField(
                                    value = state.customOutputTemplate,
                                    onValueChange = { viewModel.setCustomOutputTemplate(it) },
                                    label = { Text(stringResource(R.string.download_custom_template_label)) },
                                    singleLine = true,
                                    enabled = !state.isRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Text(
                                stringResource(R.string.download_concurrent_fragments, state.concurrentFragments),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 2, 4, 8).forEach { count ->
                                    FilterChip(
                                        selected = state.concurrentFragments == count,
                                        onClick = { viewModel.setConcurrentFragments(count) },
                                        enabled = !state.isRunning,
                                        label = { Text("${count}x") },
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.download_write_subs), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.writeSubtitles,
                                    onCheckedChange = { viewModel.setWriteSubtitles(it) },
                                    enabled = !state.isRunning,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.download_force_ipv4), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.forceIpv4,
                                    onCheckedChange = { viewModel.setForceIpv4(it) },
                                    enabled = !state.isRunning,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.download_geo_bypass), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.geoBypass,
                                    onCheckedChange = { viewModel.setGeoBypass(it) },
                                    enabled = !state.isRunning,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.download_no_check_certs), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.noCheckCertificates,
                                    onCheckedChange = { viewModel.setNoCheckCertificates(it) },
                                    enabled = !state.isRunning,
                                )
                            }

                            OutlinedTextField(
                                value = state.rateLimit,
                                onValueChange = { viewModel.setRateLimit(it) },
                                label = { Text(stringResource(R.string.download_rate_limit_label)) },
                                singleLine = true,
                                enabled = !state.isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.proxyUrl,
                                onValueChange = { viewModel.setProxyUrl(it) },
                                label = { Text(stringResource(R.string.download_proxy_label)) },
                                singleLine = true,
                                enabled = !state.isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.userAgent,
                                onValueChange = { viewModel.setUserAgent(it) },
                                label = { Text(stringResource(R.string.download_user_agent_label)) },
                                singleLine = true,
                                enabled = !state.isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.referer,
                                onValueChange = { viewModel.setReferer(it) },
                                label = { Text(stringResource(R.string.download_referer_label)) },
                                singleLine = true,
                                enabled = !state.isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.extraExtractorArgs,
                                onValueChange = { viewModel.setExtraExtractorArgs(it) },
                                label = { Text(stringResource(R.string.download_extractor_args_label)) },
                                singleLine = true,
                                enabled = !state.isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // Start / Cancel Button
        item {
            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Button(
                    onClick = startDownloadWithPermissionCheck,
                    enabled = state.url.isNotBlank() && !state.isFetchingPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.download_start))
                }
            }
        }

        // Progress Indicator
        if (state.isRunning) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (state.progress <= 0f) {
                                    stringResource(R.string.download_initializing)
                                } else {
                                    "${(state.progress * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (state.etaSeconds > 0L) {
                                Text(
                                    stringResource(R.string.download_eta, state.etaSeconds.toInt()),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (state.progress <= 0f) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        // Actionable Diagnostic Error Card
        val diag = state.errorDiagnostic
        if (diag != null) {
            item {
                DiagnosticErrorCard(
                    diagnostic = diag,
                    showDetails = showErrorDetails,
                    isUpdatingEngine = state.isUpdatingEngine,
                    onToggleDetails = { showErrorDetails = !showErrorDetails },
                    onRetry = startDownloadWithPermissionCheck,
                    onAddCookies = { showCookieSheet = true },
                    onEnableIpv4 = { viewModel.setForceIpv4(true) },
                    onUpdateEngine = { viewModel.updateEngine() },
                )
            }
        }

        // Result Card (Single or Multi-Item / Video / Cover / Subtitles with YTDLnis Properties)
        val res = state.result
        if (res != null && (state.savedMediaEntries.isNotEmpty() || res.imageFile != null || state.savedSubtitlePaths.isNotEmpty())) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val imgFile = res.imageFile
                        val primaryDurSec = state.savedMediaEntries.firstOrNull()?.item?.durationSec ?: 0
                        if (imgFile != null) {
                            val bmp = remember(imgFile.absolutePath) {
                                runCatching { BitmapFactory.decodeFile(imgFile.absolutePath)?.asImageBitmap() }.getOrNull()
                            }
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                ) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = res.metadata?.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (primaryDurSec > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Black.copy(alpha = 0.78f),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp),
                                        ) {
                                            Text(
                                                text = Formatters.formatDurationMs(
                                                    primaryDurSec * 1000L,
                                                    includeMillis = false,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (state.savedMediaEntries.size <= 1) {
                            res.metadata?.let { meta ->
                                if (meta.title.isNotBlank()) {
                                    Text(
                                        text = meta.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val firstMedia = state.savedMediaEntries.firstOrNull()?.item
                                val byline = listOf(
                                    meta.artist,
                                    meta.album,
                                    meta.year,
                                    firstMedia?.formatLabel.orEmpty(),
                                    firstMedia?.file?.length()?.takeIf { it > 0L }?.let { Formatters.formatBytes(it) }.orEmpty(),
                                ).filter { it.isNotBlank() }.joinToString(" • ")
                                if (byline.isNotBlank()) {
                                    Text(
                                        text = byline,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }

                        state.savedMediaEntries.forEachIndexed { idx, entry ->
                            if (idx > 0) HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (state.savedMediaEntries.size > 1) {
                                    Text(
                                        text = entry.item.metadata?.title?.takeIf { it.isNotBlank() } ?: entry.item.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                entry.savedPath?.let { p ->
                                    Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            onOpenInEditor(
                                                entry.item.file,
                                                entry.item.displayName,
                                                res.imageFile,
                                                res.imageMimeType,
                                                entry.item.metadata ?: res.metadata,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (entry.item.isVideo) {
                                                stringResource(R.string.download_extract_to_editor)
                                            } else {
                                                stringResource(R.string.download_open_in_editor)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val meta = entry.item.metadata ?: res.metadata
                                            activeProperties = MediaPropertiesPayload(
                                                title = meta?.title?.takeIf { it.isNotBlank() } ?: entry.item.displayName,
                                                coverPath = res.imageFile?.absolutePath,
                                                artist = meta?.artist.orEmpty(),
                                                album = meta?.album.orEmpty(),
                                                year = meta?.year.orEmpty(),
                                                durationSec = entry.item.durationSec,
                                                formatLabel = entry.item.formatLabel,
                                                sizeBytes = entry.item.file.length(),
                                                savedPath = entry.savedPath ?: entry.item.displayName,
                                                sourceUrl = res.sourceUrl ?: state.url.trim(),
                                            )
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Info,
                                            contentDescription = stringResource(R.string.download_properties_action),
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onShareFile(entry.item.file, entry.item.mimeType) },
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.export_share))
                                    }
                                }
                            }
                        }

                        state.savedImagePath?.let { p ->
                            Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                        }

                        if (state.savedSubtitlePaths.isNotEmpty()) {
                            Text(
                                stringResource(R.string.download_saved_subtitles, state.savedSubtitlePaths.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (res.imageFile != null && (hasActiveEditorSession || state.savedMediaEntries.isEmpty())) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (hasActiveEditorSession) {
                                    FilledTonalButton(
                                        onClick = { onUseAsCover(res.imageFile, res.imageMimeType ?: "image/jpeg") },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.download_set_as_cover))
                                    }
                                }
                                if (state.savedMediaEntries.isEmpty()) {
                                    OutlinedButton(
                                        onClick = { onShareFile(res.imageFile, res.imageMimeType ?: "image/*") },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.export_share))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Console Output
        if (state.logs.isNotEmpty()) {
            item {
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(stringResource(R.string.download_logs))
                }
                AnimatedVisibility(visible = showLogs) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.logs.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        // Persistent Download History (with YTDLnis-style cover thumbnails & properties)
        if (state.downloadHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.download_history_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { viewModel.clearDownloadHistory() }) {
                        Text(stringResource(R.string.download_history_clear))
                    }
                }
            }
            items(state.downloadHistory, key = { it.id }) { entry ->
                val missingMsg = stringResource(R.string.download_history_file_missing)
                val cachedFile = entry.cacheFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val coverFile = entry.coverFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val coverBmp = remember(entry.coverFilePath) {
                    coverFile?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
                }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (coverBmp != null) {
                                    Image(
                                        bitmap = coverBmp,
                                        contentDescription = entry.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        imageVector = when {
                                            entry.mimeType.startsWith("image/") -> Icons.Rounded.Image
                                            entry.isVideo -> Icons.Rounded.Movie
                                            else -> Icons.Rounded.AudioFile
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val detailParts = buildList {
                                    if (entry.artist.isNotBlank()) add(entry.artist)
                                    if (entry.formatLabel.isNotBlank()) add(entry.formatLabel)
                                    if (entry.durationSec > 0) {
                                        add(Formatters.formatDurationMs(entry.durationSec * 1000L, includeMillis = false))
                                    }
                                    if (entry.sizeBytes > 0L) add(Formatters.formatBytes(entry.sizeBytes))
                                }
                                if (detailParts.isNotEmpty()) {
                                    Text(
                                        text = detailParts.joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (entry.savedPath.isNotBlank()) {
                                    Text(
                                        text = entry.savedPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!entry.mimeType.startsWith("image/")) {
                                FilledTonalButton(
                                    onClick = {
                                        when {
                                            cachedFile != null -> {
                                                val restoredMeta = AudioMetadata(
                                                    title = entry.title,
                                                    artist = entry.artist,
                                                    album = entry.album,
                                                    year = entry.year,
                                                    comment = entry.sourceUrl,
                                                ).takeUnless { it.isEmpty }
                                                onOpenInEditor(
                                                    cachedFile,
                                                    entry.title,
                                                    coverFile,
                                                    if (coverFile != null) "image/jpeg" else null,
                                                    restoredMeta,
                                                )
                                            }
                                            !entry.contentUriString.isNullOrBlank() -> onOpenHistoryUri(entry.contentUriString)
                                            else -> onShowMessage(missingMsg)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (entry.isVideo) {
                                            stringResource(R.string.download_extract_to_editor)
                                        } else {
                                            stringResource(R.string.download_open_in_editor)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            IconButton(
                                onClick = {
                                    activeProperties = MediaPropertiesPayload(
                                        title = entry.title,
                                        coverPath = coverFile?.absolutePath,
                                        artist = entry.artist,
                                        album = entry.album,
                                        year = entry.year,
                                        durationSec = entry.durationSec,
                                        formatLabel = entry.formatLabel.ifBlank { entry.mimeType },
                                        sizeBytes = entry.sizeBytes,
                                        savedPath = entry.savedPath,
                                        sourceUrl = entry.sourceUrl,
                                    )
                                },
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.download_properties_action))
                            }
                            IconButton(
                                onClick = {
                                    when {
                                        cachedFile != null -> onShareFile(cachedFile, entry.mimeType)
                                        !entry.contentUriString.isNullOrBlank() -> onShareHistoryUri(entry.contentUriString, entry.mimeType)
                                        else -> onShowMessage(missingMsg)
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.export_share))
                            }
                            IconButton(
                                onClick = { viewModel.setUrl(entry.sourceUrl) },
                                enabled = entry.sourceUrl.isNotBlank(),
                            ) {
                                Icon(Icons.Rounded.Replay, contentDescription = stringResource(R.string.download_history_refill))
                            }
                            IconButton(
                                onClick = { viewModel.deleteHistoryEntry(entry.id) },
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.download_history_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    val propsPayload = activeProperties
    if (propsPayload != null) {
        MediaPropertiesSheet(
            payload = propsPayload,
            onDismiss = { activeProperties = null },
        )
    }

    if (showCookieSheet) {
        CookieManagerSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = {
                viewModel.clearCookieError()
                showCookieSheet = false
            },
        )
    }

    val sheetPreview = state.preview
    if (showFormatSheet && sheetPreview != null) {
        FormatPickerSheet(
            formats = sheetPreview.formats,
            selectedFormatId = state.selectedFormatId,
            onSelectFormat = { id ->
                viewModel.selectFormatId(id)
                showFormatSheet = false
            },
            onDismiss = { showFormatSheet = false },
        )
    }

    if (showSaveTemplateDialog) {
        var templateName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            title = { Text(stringResource(R.string.download_save_template)) },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text(stringResource(R.string.download_template_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCommandTemplate(templateName)
                        showSaveTemplateDialog = false
                    },
                    enabled = templateName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPickerSheet(
    formats: List<MediaFormatOption>,
    selectedFormatId: String?,
    onSelectFormat: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var activeTab by remember { mutableIntStateOf(0) }
    val audioFormats = remember(formats) { formats.filter { it.isAudioOnly } }
    val muxedFormats = remember(formats) { formats.filter { it.isMuxedVideo } }
    val videoOnlyFormats = remember(formats) { formats.filter { it.isVideoOnly } }

    val displayed = when (activeTab) {
        0 -> audioFormats
        1 -> muxedFormats
        else -> videoOnlyFormats
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.formats_picker_title), style = MaterialTheme.typography.headlineSmall)

            OutlinedCard(
                onClick = { onSelectFormat(null) },
                colors = if (selectedFormatId == null) {
                    CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                } else {
                    CardDefaults.outlinedCardColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = selectedFormatId == null, onClick = { onSelectFormat(null) })
                    Text(stringResource(R.string.formats_picker_auto), style = MaterialTheme.typography.titleSmall)
                }
            }

            val tabs = listOf(
                "${stringResource(R.string.formats_tab_audio)} (${audioFormats.size})",
                "${stringResource(R.string.formats_tab_video)} (${muxedFormats.size})",
                "${stringResource(R.string.formats_tab_other)} (${videoOnlyFormats.size})",
            )
            PrimaryTabRow(selectedTabIndex = activeTab) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = activeTab == idx,
                        onClick = { activeTab = idx },
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (displayed.isEmpty()) {
                    Text(
                        text = stringResource(R.string.formats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    displayed.forEach { fmt ->
                        val selected = selectedFormatId == fmt.formatId
                        OutlinedCard(
                            onClick = { onSelectFormat(fmt.formatId) },
                            colors = if (selected) {
                                CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                )
                            } else {
                                CardDefaults.outlinedCardColors()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RadioButton(selected = selected, onClick = { onSelectFormat(fmt.formatId) })
                                Column(modifier = Modifier.weight(1f)) {
                                    val headline = listOfNotNull(
                                        "#${fmt.formatId}",
                                        fmt.ext.uppercase(),
                                        fmt.resolution.takeIf { it.isNotBlank() && it != "audio only" },
                                        fmt.formatNote.takeIf { it.isNotBlank() },
                                    ).joinToString(" • ")
                                    Text(headline, style = MaterialTheme.typography.titleSmall)

                                    val details = buildList {
                                        val kbps = maxOf(fmt.abrKbps, fmt.tbrKbps)
                                        if (kbps > 0) add("$kbps kbps")
                                        if (fmt.asrHz > 0) add("${fmt.asrHz / 1000} kHz")
                                        if (fmt.acodec.isNotBlank() && fmt.acodec != "none") add(fmt.acodec)
                                        if (fmt.vcodec.isNotBlank() && fmt.vcodec != "none") add(fmt.vcodec)
                                        if (fmt.fileSizeBytes > 0L) add(Formatters.formatBytes(fmt.fileSizeBytes))
                                    }
                                    if (details.isNotEmpty()) {
                                        Text(
                                            text = details.joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookieManagerSheet(
    state: DownloadUiState,
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pastedText by remember { mutableStateOf("") }
    var showWebLogin by remember { mutableStateOf(false) }

    val cookieFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importCookiesFromUri(uri) { success ->
                if (success) onDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.cookies_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.cookies_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.cookieSummary?.let { summary ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                R.string.cookies_active_summary,
                                summary.cookieCount,
                                summary.domains.take(4).joinToString(", ").ifBlank { "cookies.txt" },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (summary.convertedFromJson) {
                            Text(
                                text = stringResource(R.string.cookies_json_converted, summary.cookieCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            if (PlatformCookieWebLogin.isSupported) {
                Button(
                    onClick = { showWebLogin = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cookies_signin_webview))
                }
            }

            FilledTonalButton(
                onClick = {
                    cookieFilePicker.launch(arrayOf("text/plain", "application/json", "text/*", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cookies_import_file))
            }

            OutlinedTextField(
                value = pastedText,
                onValueChange = {
                    pastedText = it
                    if (state.cookieImportError != null) viewModel.clearCookieError()
                },
                label = { Text(stringResource(R.string.cookies_paste_label)) },
                placeholder = { Text(stringResource(R.string.cookies_paste_hint)) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.cookieImportError != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.cookieImportError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            if (viewModel.importCookiesFromText(pastedText)) {
                                pastedText = ""
                                onDismiss()
                            }
                        }
                    },
                    enabled = pastedText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cookies_save_validate))
                }
                if (state.cookieSummary != null) {
                    OutlinedButton(
                        onClick = { viewModel.clearCookies() },
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cookies_clear))
                    }
                }
            }
        }
    }

    if (showWebLogin && PlatformCookieWebLogin.isSupported) {
        PlatformCookieWebLogin.LoginDialog(
            initialUrl = state.url,
            onCookiesExtracted = { netscapeText ->
                scope.launch {
                    if (viewModel.importCookiesFromText(netscapeText)) {
                        showWebLogin = false
                        onDismiss()
                    }
                }
            },
            onDismiss = { showWebLogin = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPropertiesSheet(
    payload: MediaPropertiesPayload,
    onDismiss: () -> Unit,
) {
    val coverBmp = remember(payload.coverPath) {
        payload.coverPath?.let { p ->
            File(p).takeIf { it.exists() && it.length() > 0L }?.let { f ->
                runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.download_properties_title), style = MaterialTheme.typography.headlineSmall)

            if (coverBmp != null) {
                Image(
                    bitmap = coverBmp,
                    contentDescription = payload.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }

            Text(
                text = payload.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            HorizontalDivider()

            payload.artist.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_channel), it)
            }
            payload.album.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_album), it)
            }
            payload.year.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_year), it)
            }
            if (payload.durationSec > 0) {
                PropertyRow(
                    stringResource(R.string.download_prop_duration),
                    Formatters.formatDurationMs(payload.durationSec * 1000L, includeMillis = false),
                )
            }
            payload.formatLabel.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_format), it)
            }
            if (payload.sizeBytes > 0L) {
                PropertyRow(stringResource(R.string.download_prop_size), Formatters.formatBytes(payload.sizeBytes))
            }
            payload.savedPath.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_path), it)
            }
            payload.sourceUrl.takeIf { it.isNotBlank() }?.let {
                PropertyRow(stringResource(R.string.download_prop_source), it)
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticErrorCard(
    diagnostic: YtDlpErrorDiagnostic,
    showDetails: Boolean,
    isUpdatingEngine: Boolean,
    onToggleDetails: () -> Unit,
    onRetry: () -> Unit,
    onAddCookies: () -> Unit,
    onEnableIpv4: () -> Unit,
    onUpdateEngine: () -> Unit,
) {
    val friendlyMsg = when (diagnostic.kind) {
        YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_bot_check)
        YtDlpErrorKind.AGE_RESTRICTED_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_age)
        YtDlpErrorKind.PRIVATE_OR_AUTH_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_auth)
        YtDlpErrorKind.HTTP_403_FORBIDDEN -> stringResource(R.string.ytdlp_err_403)
        YtDlpErrorKind.EXTRACTOR_OR_FORMAT_FAILED -> stringResource(R.string.ytdlp_err_extractor_format)
        YtDlpErrorKind.FFMPEG_REQUIRED -> stringResource(R.string.ytdlp_err_ffmpeg)
        YtDlpErrorKind.NETWORK_OR_TIMEOUT -> stringResource(R.string.ytdlp_err_network)
        YtDlpErrorKind.UNSUPPORTED_URL -> stringResource(R.string.ytdlp_err_unsupported)
        YtDlpErrorKind.UNAVAILABLE -> stringResource(R.string.ytdlp_err_unavailable)
        YtDlpErrorKind.UNKNOWN -> stringResource(R.string.ytdlp_err_generic_retry)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = friendlyMsg,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val needsCookies = diagnostic.kind in setOf(
                YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES,
                YtDlpErrorKind.AGE_RESTRICTED_NEEDS_COOKIES,
                YtDlpErrorKind.PRIVATE_OR_AUTH_NEEDS_COOKIES,
                YtDlpErrorKind.HTTP_403_FORBIDDEN,
                YtDlpErrorKind.EXTRACTOR_OR_FORMAT_FAILED,
                YtDlpErrorKind.UNKNOWN,
            )
            val canTryIpv4 = diagnostic.kind in setOf(
                YtDlpErrorKind.HTTP_403_FORBIDDEN,
                YtDlpErrorKind.NETWORK_OR_TIMEOUT,
                YtDlpErrorKind.UNKNOWN,
            )
            val canTryUpdate = diagnostic.kind in setOf(
                YtDlpErrorKind.HTTP_403_FORBIDDEN,
                YtDlpErrorKind.EXTRACTOR_OR_FORMAT_FAILED,
                YtDlpErrorKind.UNSUPPORTED_URL,
                YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES,
                YtDlpErrorKind.UNKNOWN,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(onClick = onRetry) {
                    Text(stringResource(R.string.ytdlp_action_retry))
                }
                if (canTryUpdate) {
                    OutlinedButton(onClick = onUpdateEngine, enabled = !isUpdatingEngine) {
                        Text(
                            if (isUpdatingEngine) {
                                stringResource(R.string.settings_ytdlp_updating)
                            } else {
                                stringResource(R.string.ytdlp_action_update_engine)
                            },
                        )
                    }
                }
                if (needsCookies) {
                    OutlinedButton(onClick = onAddCookies) {
                        Text(stringResource(R.string.ytdlp_action_add_cookies))
                    }
                }
                if (canTryIpv4) {
                    OutlinedButton(onClick = onEnableIpv4) {
                        Text(stringResource(R.string.ytdlp_action_force_ipv4))
                    }
                }
            }

            if (diagnostic.rawDetails.isNotBlank()) {
                TextButton(onClick = onToggleDetails) {
                    Text(
                        stringResource(R.string.ytdlp_show_details),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                AnimatedVisibility(visible = showDetails) {
                    Text(
                        text = diagnostic.rawDetails,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        } else {
            CardDefaults.outlinedCardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
