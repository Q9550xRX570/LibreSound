// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.libreopus.app.MainActivity
import com.libreopus.app.R
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var lastUpdateMs = 0L
    private var lastNotifiedPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = PlatformDownloadCoordinator.get(applicationContext)
        if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            coordinator.cancelDownload()
            stopForegroundAndFinish()
            return START_NOT_STICKY
        }

        val current = coordinator.state.value
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS_ID,
            buildProgressNotification(current),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (!current.isRunning) {
            handleCompletionNotification(current)
            stopForegroundAndFinish()
            return START_NOT_STICKY
        }

        if (observerJob == null) {
            observerJob = serviceScope.launch {
                coordinator.state.collect { snap ->
                    if (snap.isRunning) {
                        val now = System.currentTimeMillis()
                        val pct = (snap.progress * 100f).roundToInt().coerceIn(0, 100)
                        if (pct != lastNotifiedPct || now - lastUpdateMs >= 450L) {
                            lastNotifiedPct = pct
                            lastUpdateMs = now
                            updateProgressNotification(snap)
                        }
                    } else {
                        handleCompletionNotification(snap)
                        stopForegroundAndFinish()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        PlatformDownloadCoordinator.get(applicationContext).cancelDownload()
        stopForegroundAndFinish()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundAndFinish() {
        observerJob?.cancel()
        observerJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updateProgressNotification(state: BackgroundDownloadState) {
        if (!canPostNotifications()) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_PROGRESS_ID, buildProgressNotification(state))
    }

    private fun buildProgressNotification(state: BackgroundDownloadState): Notification {
        val title = state.activeTitle.takeIf { it.isNotBlank() }
            ?: getString(R.string.download_notif_running_title)
        val pct = (state.progress * 100f).roundToInt().coerceIn(0, 100)
        val indeterminate = pct == 0 && state.etaSeconds < 0L
        val statusText = when {
            indeterminate -> state.logs.lastOrNull()?.take(80) ?: getString(R.string.download_notif_preparing)
            state.etaSeconds >= 0L -> "$pct% • ${getString(R.string.download_eta, state.etaSeconds)}"
            else -> "$pct%"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            REQ_CANCEL,
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .setProgress(100, pct, indeterminate)
            .addAction(0, getString(R.string.download_notif_cancel), cancelIntent)
            .build()
    }

    private fun handleCompletionNotification(state: BackgroundDownloadState) {
        if (!canPostNotifications()) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val openAppIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val result = state.result
        val diag = state.errorDiagnostic
        if (result != null) {
            val itemCount = state.savedMediaEntries.size
            val detail = when {
                itemCount > 1 -> "${state.savedAudioPath ?: result.audioDisplayName} (+${itemCount - 1})"
                else -> state.savedAudioPath ?: state.savedImagePath ?: result.audioDisplayName ?: state.activeTitle
            }
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(getString(R.string.download_notif_complete_title))
                .setContentText(detail)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()
            nm.notify(NOTIF_DONE_ID, notif)
        } else if (diag != null) {
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(getString(R.string.download_notif_failed_title))
                .setContentText(diag.rawDetails.lineSequence().firstOrNull().orEmpty().take(120))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()
            nm.notify(NOTIF_DONE_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "libreopus_downloads"
        private const val NOTIF_PROGRESS_ID = 4201
        private const val NOTIF_DONE_ID = 4202
        private const val REQ_OPEN_APP = 1001
        private const val REQ_CANCEL = 1002
        private const val ACTION_CANCEL_DOWNLOAD = "com.libreopus.app.action.CANCEL_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
