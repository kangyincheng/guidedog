package com.guidedog.app.utils

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TtsManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val isInitializedLock = ReentrantLock()
    private val isSpeaking = AtomicBoolean(false)
    private val currentUtteranceId = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var cooldownMs = 300L
    private var lastSpeakTime = 0L
    private var lastSpeakText = ""
    private val speakLock = Any()

    private val pendingQueue = ArrayDeque<String>()
    private val queueLock = Any()
    private var onInitComplete: ((Boolean) -> Unit)? = null
    private var initRetryCount = 0
    private val maxInitRetries = 2

    fun init(callback: ((Boolean) -> Unit)? = null) {
        if (isInitialized.get()) {
            callback?.invoke(true)
            return
        }

        isInitializedLock.withLock {
            if (isInitialized.get()) {
                callback?.invoke(true)
                return
            }

            onInitComplete = callback
            createTts()
        }
    }

    private fun createTts() {
        textToSpeech = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS
            if (success) {
                setupTtsParams()
                setupUtteranceListener()
                initRetryCount = 0
            } else if (initRetryCount < maxInitRetries) {
                initRetryCount++
                mainHandler.postDelayed({
                    createTts()
                }, 500L)
                return@TextToSpeech
            }

            isInitialized.set(success)
            val cb = onInitComplete
            onInitComplete = null
            cb?.invoke(success)
        }
    }

    private fun setupTtsParams() {
        textToSpeech?.let { tts ->
            tts.setSpeechRate(speechRate)
            tts.setPitch(pitch)
        }
    }

    private fun setupUtteranceListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking.set(true)
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking.set(false)
                processNextInQueue()
            }

            override fun onError(utteranceId: String?) {
                isSpeaking.set(false)
                processNextInQueue()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                isSpeaking.set(false)
                if (interrupted) {
                    synchronized(queueLock) {
                        pendingQueue.clear()
                    }
                } else {
                    processNextInQueue()
                }
            }
        })
    }

    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return

        if (!isInitialized.get()) {
            init { success ->
                if (success) {
                    doSpeak(text, flush)
                }
            }
            return
        }

        doSpeak(text, flush)
    }

    private fun doSpeak(text: String, flush: Boolean) {
        synchronized(speakLock) {
            val currentTime = System.currentTimeMillis()

            if (text == lastSpeakText && currentTime - lastSpeakTime < cooldownMs) {
                return
            }

            lastSpeakText = text
            lastSpeakTime = currentTime
        }

        if (flush) {
            stopInternal()
            speakInternal(text)
        } else {
            synchronized(queueLock) {
                if (isSpeaking.get()) {
                    pendingQueue.add(text)
                } else {
                    speakInternal(text)
                }
            }
        }
    }

    private fun speakInternal(text: String) {
        val utteranceId = generateUtteranceId()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        try {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (_: Exception) {
            isSpeaking.set(false)
            processNextInQueue()
        }
    }

    private fun processNextInQueue() {
        synchronized(queueLock) {
            if (pendingQueue.isNotEmpty() && !isSpeaking.get()) {
                val nextText = pendingQueue.removeFirst()
                speakInternal(nextText)
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        synchronized(queueLock) {
            pendingQueue.clear()
        }
        if (isInitialized.get()) {
            try {
                textToSpeech?.stop()
            } catch (_: Exception) {
            }
            isSpeaking.set(false)
        }
    }

    fun shutdown() {
        stopInternal()
        try {
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
        isInitialized.set(false)
        initRetryCount = 0
        synchronized(speakLock) {
            lastSpeakText = ""
            lastSpeakTime = 0L
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 3.0f)
        if (isInitialized.get()) {
            try {
                textToSpeech?.setSpeechRate(speechRate)
            } catch (_: Exception) {
            }
        }
    }

    fun getSpeechRate(): Float = speechRate

    fun setPitch(pitchValue: Float) {
        pitch = pitchValue.coerceIn(0.1f, 3.0f)
        if (isInitialized.get()) {
            try {
                textToSpeech?.setPitch(pitch)
            } catch (_: Exception) {
            }
        }
    }

    fun getPitch(): Float = pitch

    fun setCooldown(cooldownMillis: Long) {
        cooldownMs = cooldownMillis.coerceAtLeast(0L)
    }

    fun getCooldown(): Long = cooldownMs

    fun isReady(): Boolean = isInitialized.get()

    fun isCurrentlySpeaking(): Boolean = isSpeaking.get()

    private fun generateUtteranceId(): String {
        return "guidedog_${currentUtteranceId.getAndIncrement()}"
    }

    companion object {
        @Volatile
        private var INSTANCE: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TtsManager(context).also { INSTANCE = it }
            }
        }
    }
}
