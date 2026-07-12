package com.guidedog.app

import android.app.Application
import com.guidedog.app.utils.TtsManager

class GuideDogApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        initTts()
    }

    private fun initTts() {
        TtsManager.getInstance(this).init { success ->
            if (success) {
                TtsManager.getInstance(this).setSpeechRate(1.0f)
            }
        }
    }

    companion object {
        lateinit var instance: GuideDogApplication
            private set
    }
}
