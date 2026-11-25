// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import com.libreopus.app.BuildConfig
import com.libreopus.app.R
import com.libreopus.app.data.AppLanguage
import com.libreopus.app.data.UserPreferences
import com.libreopus.app.downloader.YtDlpUpdateChannel
import com.libreopus.app.downloader.YtDlpUpdateStatus
import com.libreopus.app.ui.theme.ThemeMode
import com.libreopus.app.viewmodel.DownloadUiState
import com.libreopus.audio.api.ExportFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    prefs: UserPreferences,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onDefaultFormatChange: (ExportFormat) -> Unit,
    onDefaultBitrateChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    downloadState: DownloadUiState? = null,
    onUpdateChannelChange: (YtDlpUpdateChannel) -> Unit = {},
    onCheckYtDlpUpdate: () -> Unit = {},
) {
    val resources = LocalResources.current
    var showLicenseDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)

                    Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.themeMode == ThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                            label = { Text(stringResource(R.string.settings_theme_system)) },
                        )
                        FilterChip(
                            selected = prefs.themeMode == ThemeMode.LIGHT,
                            onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                            label = { Text(stringResource(R.string.settings_theme_light)) },
                        )
                        FilterChip(
                            selected = prefs.themeMode == ThemeMode.DARK,
                            onClick = { onThemeModeChange(ThemeMode.DARK) },
                            label = { Text(stringResource(R.string.settings_theme_dark)) },
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.settings_dynamic_color))
                            Switch(checked = prefs.dynamicColor, onCheckedChange = onDynamicColorChange)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_amoled))
                        Switch(checked = prefs.amoledBlack, onCheckedChange = onAmoledBlackChange)
                    }

                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.language == AppLanguage.SYSTEM,
                            onClick = { onLanguageChange(AppLanguage.SYSTEM) },
                            label = { Text(stringResource(R.string.settings_lang_system)) },
                        )
                        FilterChip(
                            selected = prefs.language == AppLanguage.EN,
                            onClick = { onLanguageChange(AppLanguage.EN) },
                            label = { Text(stringResource(R.string.settings_lang_en)) },
                        )
                        FilterChip(
                            selected = prefs.language == AppLanguage.TR,
                            onClick = { onLanguageChange(AppLanguage.TR) },
                            label = { Text(stringResource(R.string.settings_lang_tr)) },
                        )
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.settings_defaults), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = prefs.defaultExportFormat == fmt,
                                onClick = { onDefaultFormatChange(fmt) },
                                label = { Text(fmt.displayName) },
                            )
                        }
                    }
                    Text(stringResource(R.string.export_bitrate, prefs.defaultBitrateKbps))
                    @Suppress("DEPRECATION")
                    Slider(
                        value = prefs.defaultBitrateKbps.toFloat(),
                        onValueChange = { onDefaultBitrateChange((it / 8f).roundToInt() * 8) },
                        valueRange = 24f..320f,
                    )
                }
            }
        }

        if (BuildConfig.HAS_YTDLP && downloadState != null) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.settings_ytdlp_title), style = MaterialTheme.typography.titleMedium)
                        val verLabel = downloadState.engineStatus.version
                            ?: downloadState.engineStatus.versionName
                            ?: stringResource(R.string.settings_ytdlp_bundled)
                        Text(
                            text = stringResource(R.string.settings_ytdlp_version, verLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(stringResource(R.string.settings_ytdlp_channel), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = downloadState.updateChannel == YtDlpUpdateChannel.STABLE,
                                onClick = { onUpdateChannelChange(YtDlpUpdateChannel.STABLE) },
                                enabled = !downloadState.isUpdatingEngine,
                                label = { Text(stringResource(R.string.settings_ytdlp_stable)) },
                            )
                            FilterChip(
                                selected = downloadState.updateChannel == YtDlpUpdateChannel.NIGHTLY,
                                onClick = { onUpdateChannelChange(YtDlpUpdateChannel.NIGHTLY) },
                                enabled = !downloadState.isUpdatingEngine,
                                label = { Text(stringResource(R.string.settings_ytdlp_nightly)) },
                            )
                        }

                        FilledTonalButton(
                            onClick = onCheckYtDlpUpdate,
                            enabled = !downloadState.isUpdatingEngine && !downloadState.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (downloadState.isUpdatingEngine) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_ytdlp_updating))
                            } else {
                                Text(stringResource(R.string.settings_ytdlp_update_btn))
                            }
                        }

                        downloadState.engineUpdateOutcome?.let { outcome ->
                            val msg = when (outcome.status) {
                                YtDlpUpdateStatus.UPDATED -> stringResource(
                                    R.string.settings_ytdlp_updated,
                                    outcome.version ?: outcome.versionName ?: "",
                                )
                                YtDlpUpdateStatus.ALREADY_UP_TO_DATE -> stringResource(R.string.settings_ytdlp_up_to_date)
                                YtDlpUpdateStatus.UNAVAILABLE -> ""
                            }
                            if (msg.isNotBlank()) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        downloadState.engineUpdateError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.AUDIO_ENGINE_FLAVOR),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        stringResource(R.string.settings_fdroid_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { showLicenseDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_view_license))
                    }
                }
            }
        }
    }

    if (showLicenseDialog) {
        val gplText = remember(resources) {
            runCatching {
                resources.openRawResource(R.raw.gplv3).bufferedReader().use { it.readText() }
            }.getOrDefault("GNU General Public License v3.0 (GPL-3.0-or-later)")
        }
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(R.string.settings_license_title)) },
            text = {
                Text(
                    text = gplText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}
