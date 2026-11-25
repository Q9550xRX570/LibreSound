// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.libreopus.app.R

object PlatformCookieWebLogin {
    val isSupported: Boolean = true

    private const val YOUTUBE_LOGIN_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=tr&continue=https%3A%2F%2Fm.youtube.com%2F"

    private data class LoginPreset(val label: String, val url: String)

    private val presets = listOf(
        LoginPreset("YouTube", YOUTUBE_LOGIN_URL),
        LoginPreset("YouTube Music", "https://music.youtube.com/"),
        LoginPreset("Instagram", "https://www.instagram.com/accounts/login/"),
        LoginPreset("SoundCloud", "https://soundcloud.com/signin"),
        LoginPreset("TikTok", "https://www.tiktok.com/login"),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun LoginDialog(
        initialUrl: String,
        onCookiesExtracted: (netscapeCookiesText: String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val startUrl = remember(initialUrl) { resolveInitialLoginUrl(initialUrl) }
        var currentUrl by remember { mutableStateOf(startUrl) }
        var urlInput by remember { mutableStateOf(startUrl) }
        var loadProgress by remember { mutableIntStateOf(0) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        val emptyCookiesErr = stringResource(R.string.cookies_webview_none_captured)

        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.let { wv ->
                    wv.stopLoading()
                    wv.destroy()
                }
                webViewRef = null
            }
        }

        fun navigateTo(raw: String) {
            errorMessage = null
            val target = raw.trim().let {
                if (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) {
                    it
                } else {
                    "https://$it"
                }
            }
            urlInput = target
            webViewRef?.loadUrl(target)
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                            }
                        },
                        title = {
                            Text(
                                text = stringResource(R.string.cookies_webview_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    errorMessage = null
                                    val cm = CookieManager.getInstance()
                                    cm.removeAllCookies {
                                        cm.flush()
                                        webViewRef?.clearCache(true)
                                        webViewRef?.reload()
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.cookies_webview_clear_session),
                                )
                            }
                            Button(
                                onClick = {
                                    errorMessage = null
                                    runCatching {
                                        val cm = CookieManager.getInstance()
                                        cm.flush()
                                        val collected = collectSiteCookies(cm, webViewRef?.url ?: currentUrl)
                                        CookieParser.fromWebViewCookieStrings(collected).netscapeText
                                    }.fold(
                                        onSuccess = { text -> onCookiesExtracted(text) },
                                        onFailure = { err ->
                                            errorMessage = err.localizedMessage?.takeIf { it.isNotBlank() } ?: emptyCookiesErr
                                        },
                                    )
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.cookies_webview_save))
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = currentUrl.contains(
                                    preset.url.toUri().host.orEmpty().removePrefix("www.").removePrefix("m."),
                                    ignoreCase = true,
                                ),
                                onClick = { navigateTo(preset.url) },
                                label = { Text(preset.label) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.cookies_webview_url_label)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { navigateTo(urlInput) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = stringResource(R.string.cookies_webview_go),
                            )
                        }
                    }

                    if (loadProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { loadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }

                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        factory = { ctx ->
                            val wv = WebView(ctx)
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(wv, true)
                            with(wv.settings) {
                                // Required to execute interactive account sign-in flows inside embedded login dialog.
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                val defaultUa = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault(userAgentString)
                                userAgentString = defaultUa.replace("; wv)", ")").replace("; wv;", ";")
                            }
                            wv.webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        urlInput = url
                                    }
                                    runCatching { CookieManager.getInstance().flush() }
                                }
                            }
                            wv.loadUrl(startUrl)
                            webViewRef = wv
                            wv
                        },
                    )
                }
            }
        }
    }

    private fun resolveInitialLoginUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return YOUTUBE_LOGIN_URL
        }
        val host = trimmed.toUri().host.orEmpty().lowercase()
        return if (host.contains("youtube.com") || host == "youtu.be") {
            YOUTUBE_LOGIN_URL
        } else {
            trimmed
        }
    }

    private fun collectSiteCookies(cm: CookieManager, activeUrl: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun addDomain(queryUrl: String, domainKey: String) {
            val raw = runCatching { cm.getCookie(queryUrl) }.getOrNull().orEmpty()
            if (raw.isNotBlank()) {
                val existing = out[domainKey]
                out[domainKey] = if (existing.isNullOrBlank()) raw else "$existing; $raw"
            }
        }

        val activeHost = runCatching { activeUrl.toUri().host }.getOrNull().orEmpty().lowercase()
        val isGoogleOrYoutube = activeHost.contains("youtube") ||
            activeHost.contains("google") ||
            activeUrl.isBlank()

        if (isGoogleOrYoutube) {
            addDomain("https://www.youtube.com", ".youtube.com")
            addDomain("https://m.youtube.com", ".youtube.com")
            addDomain("https://music.youtube.com", "music.youtube.com")
            addDomain("https://studio.youtube.com", "studio.youtube.com")
            addDomain("https://www.google.com", ".google.com")
            addDomain("https://accounts.google.com", "accounts.google.com")
            addDomain("https://googlevideo.com", ".googlevideo.com")
        }

        if (activeHost.isNotBlank() && activeHost.contains('.')) {
            val parts = activeHost.split('.')
            val baseDomain = if (parts.size >= 2) {
                ".${parts.takeLast(2).joinToString(".")}"
            } else {
                activeHost
            }
            addDomain("https://${baseDomain.trimStart('.')}", baseDomain)
            if (activeHost != baseDomain.trimStart('.')) {
                addDomain("https://$activeHost", activeHost)
            }
        }
        return out
    }
}
