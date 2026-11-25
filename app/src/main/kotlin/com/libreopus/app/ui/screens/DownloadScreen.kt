// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.app.data.Formatters
import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.PresetCommandBuilder
import com.libreopus.app.downloader.YtDlpErrorDiagnostic
import com.libreopus.app.downloader.YtDlpErrorKind
import com.libreopus.app.viewmodel.DownloadUiState
import com.libreopus.app.viewmodel.DownloadViewModel
import com.libreopus.audio.api.AudioMetadata
import java.io.File
import kotlinx.coroutines.launch

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
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showCookieSheet by remember { mutableStateOf(false) }
    var showErrorDetails by remember { mutableStateOf(false) }

    val execOptionsPreview = remember(
        state.audioPreference,
        state.useCookies,
        state.cookieSummary,
        state.forceIpv4,
        state.geoBypass,
        state.proxyUrl,
        state.userAgent,
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

        // URL Input + Preview Info Action
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { viewModel.setUrl(it) },
                    label = { Text(stringResource(R.string.download_url_hint)) },
                    singleLine = true,
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
                        )
                    }
                }
            }
        }

        // Preview Card (when --dump-json succeeded)
        val preview = state.preview
        if (preview != null) {
            item {
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = preview.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val metaParts = buildList {
                            if (preview.uploader.isNotBlank()) add(preview.uploader)
                            if (preview.durationSec > 0) {
                                add(Formatters.formatDurationMs(preview.durationSec * 1000L, includeMillis = false))
                            }
                            preview.audioExt?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                            if (preview.approxFileSizeBytes > 0L) {
                                add("~${Formatters.formatBytes(preview.approxFileSizeBytes)}")
                            }
                            preview.extractor?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = metaParts.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    selected = state.preset == DownloadPreset.IMAGE_ONLY,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.download_preset_image_only),
                    subtitle = stringResource(R.string.download_preset_image_only_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.IMAGE_ONLY) },
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
                    selected = state.preset == DownloadPreset.CUSTOM_SCRIPT,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.download_preset_custom),
                    subtitle = stringResource(R.string.download_preset_custom_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.CUSTOM_SCRIPT) },
                )
            }
        }

        // Audio format preference or Custom script editor
        item {
            if (state.preset == DownloadPreset.BEST_AUDIO || state.preset == DownloadPreset.AUDIO_AND_IMAGE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

            if (state.preset == DownloadPreset.CUSTOM_SCRIPT) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.customArgs,
                        onValueChange = { viewModel.setCustomArgs(it) },
                        label = { Text(stringResource(R.string.download_custom_args_label)) },
                        enabled = !state.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val quickFlags = listOf(
                            "--force-ipv4",
                            "--geo-bypass",
                            "--write-thumbnail",
                            "--playlist-items 1-5",
                            "-f bestaudio[ext=m4a]/bestaudio",
                        )
                        quickFlags.forEach { flag ->
                            AssistChip(
                                onClick = { viewModel.appendCustomFlag(flag) },
                                enabled = !state.isRunning,
                                label = { Text(flag, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.download_command_preview,
                        PresetCommandBuilder.formatPreview(state.preset, execOptionsPreview),
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Expandable Network / Proxy / Advanced options
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
                    onClick = { viewModel.startDownload() },
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
                    onAddCookies = { showCookieSheet = true },
                    onEnableIpv4 = { viewModel.setForceIpv4(true) },
                    onUpdateEngine = { viewModel.updateEngine() },
                )
            }
        }

        // Result Card
        val res = state.result
        if (res != null && (res.audioFile != null || res.imageFile != null)) {
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
                        if (imgFile != null) {
                            val bmp = remember(imgFile.absolutePath) {
                                runCatching { BitmapFactory.decodeFile(imgFile.absolutePath)?.asImageBitmap() }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(170.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                )
                            }
                        }

                        res.metadata?.let { meta ->
                            if (meta.title.isNotBlank()) {
                                Text(
                                    text = meta.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val byline = listOf(meta.artist, meta.year).filter { it.isNotBlank() }.joinToString(" • ")
                            if (byline.isNotBlank()) {
                                Text(
                                    text = byline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }

                        state.savedAudioPath?.let { p ->
                            Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                        }
                        state.savedImagePath?.let { p ->
                            Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                        }

                        if (res.audioFile != null) {
                            Button(
                                onClick = {
                                    onOpenInEditor(
                                        res.audioFile,
                                        res.audioDisplayName ?: res.audioFile.name,
                                        res.imageFile,
                                        res.imageMimeType,
                                        res.metadata,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.download_open_in_editor))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (res.imageFile != null && hasActiveEditorSession) {
                                FilledTonalButton(
                                    onClick = { onUseAsCover(res.imageFile, res.imageMimeType ?: "image/jpeg") },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.download_set_as_cover))
                                }
                            }
                            val shareTarget = res.audioFile ?: res.imageFile
                            val shareMime = if (res.audioFile != null) "audio/*" else (res.imageMimeType ?: "image/*")
                            if (shareTarget != null) {
                                OutlinedButton(
                                    onClick = { onShareFile(shareTarget, shareMime) },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticErrorCard(
    diagnostic: YtDlpErrorDiagnostic,
    showDetails: Boolean,
    isUpdatingEngine: Boolean,
    onToggleDetails: () -> Unit,
    onAddCookies: () -> Unit,
    onEnableIpv4: () -> Unit,
    onUpdateEngine: () -> Unit,
) {
    val friendlyMsg = when (diagnostic.kind) {
        YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_bot_check)
        YtDlpErrorKind.AGE_RESTRICTED_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_age)
        YtDlpErrorKind.PRIVATE_OR_AUTH_NEEDS_COOKIES -> stringResource(R.string.ytdlp_err_auth)
        YtDlpErrorKind.HTTP_403_FORBIDDEN -> stringResource(R.string.ytdlp_err_403)
        YtDlpErrorKind.FFMPEG_REQUIRED -> stringResource(R.string.ytdlp_err_ffmpeg)
        YtDlpErrorKind.NETWORK_OR_TIMEOUT -> stringResource(R.string.ytdlp_err_network)
        YtDlpErrorKind.UNSUPPORTED_URL -> stringResource(R.string.ytdlp_err_unsupported)
        YtDlpErrorKind.UNAVAILABLE -> stringResource(R.string.ytdlp_err_unavailable)
        YtDlpErrorKind.UNKNOWN -> diagnostic.rawDetails.ifBlank { "Download failed" }
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
            )
            val canTryIpv4 = diagnostic.kind in setOf(
                YtDlpErrorKind.HTTP_403_FORBIDDEN,
                YtDlpErrorKind.NETWORK_OR_TIMEOUT,
            )
            val canTryUpdate = diagnostic.kind in setOf(
                YtDlpErrorKind.HTTP_403_FORBIDDEN,
                YtDlpErrorKind.UNSUPPORTED_URL,
                YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES,
            )

            if (needsCookies || canTryIpv4 || canTryUpdate) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (needsCookies) {
                        FilledTonalButton(onClick = onAddCookies) {
                            Text(stringResource(R.string.ytdlp_action_add_cookies))
                        }
                    }
                    if (canTryIpv4) {
                        OutlinedButton(onClick = onEnableIpv4) {
                            Text(stringResource(R.string.ytdlp_action_force_ipv4))
                        }
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
                }
            }

            if (diagnostic.rawDetails.isNotBlank() && diagnostic.kind != YtDlpErrorKind.UNKNOWN) {
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
