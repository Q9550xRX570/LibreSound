// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.sheets

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.app.data.Formatters
import com.libreopus.audio.api.AudioInfo
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.WavBitDepth
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSheet(
    initial: AudioMetadata,
    canSaveTagsLosslessly: Boolean,
    onPickCoverArt: (onPicked: (ByteArray, String) -> Unit) -> Unit,
    onApplyToSession: (AudioMetadata) -> Unit,
    onSaveTagsLosslessly: (AudioMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var album by remember { mutableStateOf(initial.album) }
    var albumArtist by remember { mutableStateOf(initial.albumArtist) }
    var year by remember { mutableStateOf(initial.year) }
    var track by remember { mutableStateOf(initial.trackNumber) }
    var genre by remember { mutableStateOf(initial.genre) }
    var composer by remember { mutableStateOf(initial.composer) }
    var comment by remember { mutableStateOf(initial.comment) }
    var coverBytes by remember { mutableStateOf(initial.coverArtBytes) }
    var coverMime by remember { mutableStateOf(initial.coverArtMimeType) }

    fun buildMeta() = initial.copy(
        title = title.trim(),
        artist = artist.trim(),
        album = album.trim(),
        albumArtist = albumArtist.trim(),
        year = year.trim(),
        trackNumber = track.trim(),
        genre = genre.trim(),
        composer = composer.trim(),
        comment = comment.trim(),
        coverArtBytes = coverBytes,
        coverArtMimeType = coverMime,
    )

    val bitmap = remember(coverBytes) {
        coverBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
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
            Text(stringResource(R.string.meta_title), style = MaterialTheme.typography.headlineSmall)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = {
                        onPickCoverArt { bytes, mime ->
                            coverBytes = bytes
                            coverMime = mime
                        }
                    }) {
                        Text(stringResource(R.string.meta_pick_cover))
                    }
                    if (coverBytes != null) {
                        OutlinedButton(onClick = { coverBytes = null; coverMime = null }) {
                            Text(stringResource(R.string.meta_remove_cover))
                        }
                    }
                }
            }

            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.meta_field_title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.meta_field_artist)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.meta_field_album)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it }, label = { Text(stringResource(R.string.meta_field_year)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(track, { track = it }, label = { Text(stringResource(R.string.meta_field_track)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text(stringResource(R.string.meta_field_album_artist)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(genre, { genre = it }, label = { Text(stringResource(R.string.meta_field_genre)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(composer, { composer = it }, label = { Text(stringResource(R.string.meta_field_composer)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.meta_field_comment)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onApplyToSession(buildMeta()); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.meta_apply_session))
            }
            if (canSaveTagsLosslessly) {
                FilledTonalButton(
                    onClick = { onSaveTagsLosslessly(buildMeta()); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.meta_save_tags_only))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(
    info: AudioInfo?,
    durationMs: Long,
    metadata: AudioMetadata,
    isLosslessOpusReady: Boolean,
    defaultFormat: ExportFormat,
    defaultBitrateKbps: Int,
    onExportToMusic: (ExportConfig, shareAfter: Boolean) -> Unit,
    onExportSaveAs: (ExportConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember {
        mutableStateOf(if (isLosslessOpusReady) ExportFormat.OPUS else defaultFormat)
    }
    var bitrateKbps by remember { mutableIntStateOf(defaultBitrateKbps.coerceIn(32, 320)) }
    var channelMode by remember { mutableStateOf(ChannelMode.KEEP) }
    var wavDepth by remember { mutableStateOf(WavBitDepth.PCM_16) }
    var losslessCopy by remember { mutableStateOf(isLosslessOpusReady) }

    val canUseLossless = isLosslessOpusReady &&
        (format == ExportFormat.OPUS || format == ExportFormat.OGG_OPUS) &&
        channelMode == ChannelMode.KEEP

    val estBytes = if (canUseLossless && losslessCopy && info != null && info.durationMs > 0L) {
        ((info.fileSizeBytes.toDouble() * durationMs) / info.durationMs).toLong()
    } else {
        Formatters.estimateExportSizeBytes(
            durationMs = durationMs,
            format = format,
            bitrateKbps = bitrateKbps,
            sampleRate = info?.sampleRate ?: 48000,
            channels = when (channelMode) {
                ChannelMode.MONO -> 1
                ChannelMode.STEREO -> 2
                ChannelMode.KEEP -> info?.channels ?: 2
            },
            wavBits = wavDepth.bits,
        )
    }

    fun buildConfig() = ExportConfig(
        format = format,
        bitrateKbps = bitrateKbps,
        channelMode = channelMode,
        wavBitDepth = wavDepth,
        losslessStreamCopy = canUseLossless && losslessCopy,
        metadata = metadata,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)

            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        onClick = { format = fmt },
                        label = { Text(fmt.displayName) },
                    )
                }
            }

            if (canUseLossless) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.export_lossless_copy),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = losslessCopy, onCheckedChange = { losslessCopy = it })
                }
            }

            if (format.supportsBitrate && !(canUseLossless && losslessCopy)) {
                Text(stringResource(R.string.export_bitrate, bitrateKbps))
                Slider(
                    value = bitrateKbps.toFloat(),
                    onValueChange = { bitrateKbps = (it / 8f).roundToInt() * 8 },
                    valueRange = 24f..320f,
                )
            }

            if (format == ExportFormat.WAV) {
                Text(stringResource(R.string.export_wav_depth), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WavBitDepth.entries.forEach { d ->
                        FilterChip(
                            selected = wavDepth == d,
                            onClick = { wavDepth = d },
                            label = { Text(if (d.isFloat) "32-bit Float" else "${d.bits}-bit") },
                        )
                    }
                }
            }

            if (!(canUseLossless && losslessCopy)) {
                Text(stringResource(R.string.export_channels), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelMode.entries.forEach { mode ->
                        FilterChip(
                            selected = channelMode == mode,
                            onClick = { channelMode = mode },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.export_estimated_size, Formatters.formatBytes(estBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { onExportToMusic(buildConfig(), false); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.export_save_music))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onExportSaveAs(buildConfig()); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.export_save_as))
                }
                OutlinedButton(
                    onClick = { onExportToMusic(buildConfig(), true); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.export_share))
                }
            }
        }
    }
}
