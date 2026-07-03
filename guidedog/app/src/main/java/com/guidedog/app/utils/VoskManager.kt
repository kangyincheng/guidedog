package com.guidedog.app.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class VoskManager private constructor(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private val isListening = AtomicBoolean(false)
    private val isModelLoaded = AtomicBoolean(false)

    private var onResultListener: ((String) -> Unit)? = null
    private var onPartialResultListener: ((String) -> Unit)? = null
    private var onStateChangedListener: ((Boolean) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null

    private var silenceTimeoutMs = 3000L
    private var lastSpeechTime = 0L
    private var silenceCheckRunnable: Runnable? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun initModel(onReady: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        if (isModelLoaded.get()) {
            onReady?.invoke()
            return
        }

        Thread {
            try {
                val modelDir = extractModel()
                if (modelDir == null) {
                    mainHandler.post {
                        onError?.invoke("语音模型未找到，请将模型放入assets/model目录")
                    }
                    return@Thread
                }

                LibVosk.setLogLevel(org.vosk.LibVosk.LogLevel.INFO)
                isModelLoaded.set(true)

                mainHandler.post {
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型初始化失败", e)
                mainHandler.post {
                    onError?.invoke("语音模型加载失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun extractModel(): File? {
        val modelDir = File(appContext.filesDir, "vosk-model")
        if (modelDir.exists() && modelDir.isDirectory && modelDir.listFiles()?.isNotEmpty() == true) {
            return modelDir
        }

        try {
            val assetList = appContext.assets.list("model") ?: return null
            if (assetList.isEmpty()) return null

            modelDir.mkdirs()
            copyAssetDir("model", modelDir)
            return modelDir
        } catch (e: IOException) {
            Log.e(TAG, "模型解压失败", e)
            return null
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val list = appContext.assets.list(assetPath) ?: return
        for (item in list) {
            val srcPath = "$assetPath/$item"
            val destFile = File(destDir, item)
            try {
                appContext.assets.open(srcPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                if (appContext.assets.list(srcPath)?.isNotEmpty() == true) {
                    destFile.mkdirs()
                    copyAssetDir(srcPath, destFile)
                }
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChanged: ((Boolean) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (isListening.get()) return
        if (!isModelLoaded.get()) {
            initModel(
                onReady = {
                    startListeningInternal(onResult, onPartialResult, onStateChanged, onError)
                },
                onError = onError
            )
            return
        }
        startListeningInternal(onResult, onPartialResult, onStateChanged, onError)
    }

    private fun startListeningInternal(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChanged: ((Boolean) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        this.onResultListener = onResult
        this.onPartialResultListener = onPartialResult
        this.onStateChangedListener = onStateChanged
        this.onErrorListener = onError

        try {
            val modelDir = File(appContext.filesDir, "vosk-model")
            val model = org.vosk.Model(modelDir.absolutePath)

            recognizer = Recognizer(model, 16000f)

            speechService = SpeechService(recognizer, 16000f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis?.let {
                        try {
                            val partial = JSONObject(it).optString("partial", "")
                            if (partial.isNotBlank()) {
                                lastSpeechTime = System.currentTimeMillis()
                                onPartialResultListener?.invoke(partial)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "解析部分结果失败", e)
                        }
                    }
                }

                override fun onResult(hypothesis: String?) {
                    hypothesis?.let {
                        try {
                            val text = JSONObject(it).optString("text", "")
                            if (text.isNotBlank()) {
                                lastSpeechTime = System.currentTimeMillis()
                                onResultListener?.invoke(text)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "解析结果失败", e)
                        }
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    hypothesis?.let {
                        try {
                            val text = JSONObject(it).optString("text", "")
                            if (text.isNotBlank()) {
                                lastSpeechTime = System.currentTimeMillis()
                                onResultListener?.invoke(text)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "解析最终结果失败", e)
                        }
                    }
                }

                override fun onError(e: Exception?) {
                    Log.e(TAG, "识别错误", e)
                    onErrorListener?.invoke(e?.message ?: "识别错误")
                }

                override fun onTimeout() {
                    stopListening()
                }
            })

            isListening.set(true)
            lastSpeechTime = System.currentTimeMillis()
            onStateChanged?.invoke(true)
            startSilenceCheck()

        } catch (e: Exception) {
            Log.e(TAG, "启动识别失败", e)
            onError?.invoke("启动识别失败: ${e.message}")
            isListening.set(false)
            onStateChanged?.invoke(false)
        }
    }

    fun stopListening() {
        if (!isListening.get()) return

        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "停止识别异常", e)
        }

        speechService = null
        recognizer = null
        isListening.set(false)
        stopSilenceCheck()
        onStateChangedListener?.invoke(false)
    }

    private fun startSilenceCheck() {
        stopSilenceCheck()
        silenceCheckRunnable = Runnable {
            if (isListening.get()) {
                val elapsed = System.currentTimeMillis() - lastSpeechTime
                if (elapsed > silenceTimeoutMs) {
                    stopListening()
                    onResultListener?.invoke("")
                    return@Runnable
                }
                mainHandler.postDelayed(silenceCheckRunnable!!, 500)
            }
        }
        mainHandler.postDelayed(silenceCheckRunnable!!, silenceTimeoutMs)
    }

    private fun stopSilenceCheck() {
        silenceCheckRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        silenceCheckRunnable = null
    }

    fun setSilenceTimeout(timeoutMs: Long) {
        silenceTimeoutMs = timeoutMs.coerceAtLeast(1000L)
    }

    fun shutdown() {
        stopListening()
        isModelLoaded.set(false)
        onResultListener = null
        onPartialResultListener = null
        onStateChangedListener = null
        onErrorListener = null
    }

    fun isListeningNow(): Boolean = isListening.get()

    fun isModelReady(): Boolean = isModelLoaded.get()

    companion object {
        private const val TAG = "VoskManager"

        @Volatile
        private var INSTANCE: VoskManager? = null

        fun getInstance(context: Context): VoskManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoskManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
