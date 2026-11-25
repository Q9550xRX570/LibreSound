// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import android.app.Application
import com.libreopus.audio.api.AudioEngine
import com.libreopus.engine.lite.LiteAudioEngine
import java.io.File

class LibreOpusApplication : Application() {

    lateinit var audioEngine: AudioEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val cacheRoot = File(cacheDir, "libreopus_engine").apply { mkdirs() }
        audioEngine = LiteAudioEngine(cacheRoot)
    }
}
