// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import androidx.compose.runtime.Composable

object PlatformCookieWebLogin {
    val isSupported: Boolean = false

    @Composable
    fun LoginDialog(
        initialUrl: String,
        onCookiesExtracted: (netscapeCookiesText: String) -> Unit,
        onDismiss: () -> Unit,
    ) = Unit
}
