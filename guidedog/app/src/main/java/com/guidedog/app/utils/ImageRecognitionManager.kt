package com.guidedog.app.utils

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class RecognitionRegion(val value: Int, val description: String) {
    LEFT(0, "左前方"),
    CENTER(1, "正前方"),
    RIGHT(2, "右前方")
}

data class RecognitionResult(
    val labels: List<AliyunApiUtils.ImageLabel>,
    val region: RecognitionRegion,
    val timestamp: Long
)

data class DangerObject(
    val name: String,
    val keywords: List<String>,
    val level: Int
)

class ImageRecognitionManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isRecognizing = AtomicBoolean(false)
    private val lastRecognitionTime = AtomicLong(0)

    private var recognitionIntervalMs: Long = 3000
    private var offlineIntervalMs: Long = 1500
    private var minConfidence: Double = 0.5

    private val recentResults = ConcurrentHashMap<String, Long>()
    private var resultCooldownMs: Long = 8000

    private var onResultListener: ((List<RecognitionResult>) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onBroadcastListener: ((String) -> Unit)? = null
    private var onModeChangedListener: ((RecognitionMode) -> Unit)? = null

    private val dangerObjects = listOf(
        DangerObject("台阶", listOf("台阶", "楼梯", "阶梯", "梯"), 3),
        DangerObject("车辆", listOf("汽车", "轿车", "车辆", "自行车", "电动车", "摩托车", "卡车", "公交车"), 3),
        DangerObject("障碍物", listOf("柱子", "电线杆", "树干", "石墩", "消防栓"), 2),
        DangerObject("水域", listOf("水", "河流", "湖泊", "水坑", "池塘"), 3),
        DangerObject("红绿灯", listOf("红绿灯", "交通灯", "信号灯"), 2),
        DangerObject("栏杆", listOf("栏杆", "护栏", "围栏", "铁栏"), 2),
        DangerObject("坑洼", listOf("坑", "洞", "凹陷", "井盖"), 3),
        DangerObject("行人", listOf("人", "人物", "行人", "人群"), 1)
    )

    private var pendingFrame: ByteArray? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0

    private var testImageUrl: String? = null

    private val networkMonitor = NetworkMonitor.getInstance(context)
    private val yoloManager = YoloInferenceManager.getInstance(context)

    @Volatile
    private var currentMode = RecognitionMode.ONLINE

    @Volatile
    private var yoloInitialized = false

    @Volatile
    private var onlineErrorCount = 0
    private val maxOnlineErrorCount = 3

    companion object {
        private const val TAG = "ImageRecognitionManager"

        @Volatile
        private var INSTANCE: ImageRecognitionManager? = null

        fun getInstance(context: Context): ImageRecognitionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImageRecognitionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun init(accessKeyId: String, accessKeySecret: String) {
        AliyunApiUtils.init(accessKeyId, accessKeySecret)
    }

    fun initOffline(): Boolean {
        yoloInitialized = yoloManager.init()
        if (yoloInitialized) {
            Log.i(TAG, "YOLO离线模型初始化成功")
        } else {
            Log.w(TAG, "YOLO离线模型未就绪，离线模式将不可用")
        }
        return yoloInitialized
    }

    fun startNetworkMonitor(onModeChanged: (RecognitionMode) -> Unit) {
        onModeChangedListener = onModeChanged
        networkMonitor.addOnModeChangedListener { mode ->
            currentMode = mode
            onlineErrorCount = 0
            recentResults.clear()
            Log.i(TAG, "识别模式切换至: $mode，已清空去重缓存")
            onModeChangedListener?.invoke(mode)
        }
        networkMonitor.start()
        currentMode = networkMonitor.getCurrentMode()
        Log.i(TAG, "网络监听已启动，当前模式: $currentMode")
    }

    fun stopNetworkMonitor() {
        networkMonitor.stop()
        onModeChangedListener = null
    }

    fun getCurrentMode(): RecognitionMode = currentMode

    fun setTestImageUrl(url: String) {
        testImageUrl = url
    }

    fun startRecognition(
        onResult: (List<RecognitionResult>) -> Unit = {},
        onBroadcast: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRecognizing.getAndSet(true)) return

        onResultListener = onResult
        onBroadcastListener = onBroadcast
        onErrorListener = onError
        lastRecognitionTime.set(0)

        scope.launch {
            recognitionLoop()
        }
    }

    fun stopRecognition() {
        isRecognizing.set(false)
        onResultListener = null
        onErrorListener = null
        onBroadcastListener = null
        pendingFrame = null
    }

    fun feedFrame(bytes: ByteArray, width: Int, height: Int, format: Int) {
        if (!isRecognizing.get()) return

        val interval = if (currentMode == RecognitionMode.ONLINE) recognitionIntervalMs else offlineIntervalMs
        val now = System.currentTimeMillis()
        if (now - lastRecognitionTime.get() < interval) return

        synchronized(this) {
            pendingFrame = bytes.copyOf()
            pendingWidth = width
            pendingHeight = height
        }
    }

    private suspend fun recognitionLoop() {
        while (isRecognizing.get()) {
            try {
                val now = System.currentTimeMillis()
                val interval = if (currentMode == RecognitionMode.ONLINE) recognitionIntervalMs else offlineIntervalMs

                if (now - lastRecognitionTime.get() >= interval) {
                    val frame = synchronized(this) {
                        val f = pendingFrame
                        pendingFrame = null
                        Triple(f, pendingWidth, pendingHeight)
                    }

                    if (frame.first != null && frame.second > 0 && frame.third > 0) {
                        lastRecognitionTime.set(now)
                        if (currentMode == RecognitionMode.ONLINE && testImageUrl.isNullOrEmpty()) {
                            processFrameOnline(frame.first!!, frame.second, frame.third)
                        } else if (currentMode == RecognitionMode.ONLINE && !testImageUrl.isNullOrEmpty()) {
                            processFrameWithTestUrl()
                        } else {
                            processFrameOffline(frame.first!!, frame.second, frame.third)
                        }
                    }
                }
                delay(200)
            } catch (e: Exception) {
                Log.e(TAG, "识别循环异常", e)
                delay(500)
            }
        }
    }

    private suspend fun processFrameOnline(bytes: ByteArray, width: Int, height: Int) {
        try {
            val results = mutableListOf<RecognitionResult>()

            for (region in RecognitionRegion.values()) {
                val croppedJpeg = cropNv21ToJpeg(bytes, width, height, region)
                if (croppedJpeg != null) {
                    val labels = recognizeByImageData(croppedJpeg)
                    if (labels.isNotEmpty()) {
                        results.add(
                            RecognitionResult(
                                labels = labels,
                                region = region,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            onlineErrorCount = 0

            if (results.isNotEmpty()) {
                val filteredResults = deduplicateResults(results)
                if (filteredResults.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResultListener?.invoke(filteredResults)
                        val broadcastText = generateBroadcastText(filteredResults)
                        if (broadcastText.isNotEmpty()) {
                            onBroadcastListener?.invoke(broadcastText)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "在线识别失败", e)
            onlineErrorCount++
            if (onlineErrorCount >= maxOnlineErrorCount) {
                Log.w(TAG, "在线识别连续失败${onlineErrorCount}次，临时切换至离线模式")
                currentMode = RecognitionMode.OFFLINE
                withContext(Dispatchers.Main) {
                    onModeChangedListener?.invoke(RecognitionMode.OFFLINE)
                    onErrorListener?.invoke("网络不稳定，已切换至离线模式")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onErrorListener?.invoke("在线识别失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun processFrameWithTestUrl() {
        try {
            val results = mutableListOf<RecognitionResult>()
            val labels = AliyunApiUtils.taggingImage(testImageUrl!!).getOrElse {
                Log.e(TAG, "URL识别失败", it)
                emptyList()
            }
            if (labels.isNotEmpty()) {
                results.add(
                    RecognitionResult(
                        labels = labels,
                        region = RecognitionRegion.CENTER,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            if (results.isNotEmpty()) {
                val filteredResults = deduplicateResults(results)
                if (filteredResults.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResultListener?.invoke(filteredResults)
                        val broadcastText = generateBroadcastText(filteredResults)
                        if (broadcastText.isNotEmpty()) {
                            onBroadcastListener?.invoke(broadcastText)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "URL测试识别失败", e)
            withContext(Dispatchers.Main) {
                onErrorListener?.invoke("识别失败: ${e.message}")
            }
        }
    }

    private suspend fun processFrameOffline(bytes: ByteArray, width: Int, height: Int) {
        if (!yoloInitialized) {
            yoloInitialized = yoloManager.init()
            if (!yoloInitialized) {
                Log.w(TAG, "YOLO模型未加载，离线识别不可用")
                withContext(Dispatchers.Main) {
                    onErrorListener?.invoke("离线模型未就绪")
                }
                return
            }
        }

        try {
            val detections = yoloManager.detect(bytes, width, height)

            if (detections.isEmpty()) return

            val regionMap = mutableMapOf<RecognitionRegion, MutableList<AliyunApiUtils.ImageLabel>>()
            for (det in detections) {
                val region = det.region
                val label = AliyunApiUtils.ImageLabel(
                    name = det.chineseName,
                    confidence = det.confidence.toDouble()
                )
                regionMap.getOrPut(region) { mutableListOf() }.add(label)
            }

            val results = regionMap.map { (region, labels) ->
                RecognitionResult(
                    labels = labels,
                    region = region,
                    timestamp = System.currentTimeMillis()
                )
            }

            val filteredResults = deduplicateResults(results)
            if (filteredResults.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onResultListener?.invoke(filteredResults)
                    val broadcastText = generateBroadcastText(filteredResults)
                    if (broadcastText.isNotEmpty()) {
                        onBroadcastListener?.invoke(broadcastText)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "离线识别失败", e)
            withContext(Dispatchers.Main) {
                onErrorListener?.invoke("离线识别失败: ${e.message}")
            }
        }
    }

    private fun cropNv21ToJpeg(
        nv21Bytes: ByteArray,
        width: Int,
        height: Int,
        region: RecognitionRegion
    ): ByteArray? {
        return try {
            val cropWidth = width / 3
            val left = when (region) {
                RecognitionRegion.LEFT -> 0
                RecognitionRegion.CENTER -> cropWidth
                RecognitionRegion.RIGHT -> cropWidth * 2
            }

            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(left, 0, left + cropWidth, height), 50, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "裁切图像失败", e)
            null
        }
    }

    private suspend fun recognizeByImageData(jpegBytes: ByteArray): List<AliyunApiUtils.ImageLabel> {
        return AliyunApiUtils.taggingImageWithBytes(jpegBytes).getOrElse {
            Log.e(TAG, "图像识别失败", it)
            emptyList()
        }
    }

    private fun deduplicateResults(results: List<RecognitionResult>): List<RecognitionResult> {
        val now = System.currentTimeMillis()
        val filteredResults = mutableListOf<RecognitionResult>()

        for (result in results) {
            val newLabels = result.labels
                .filter { it.confidence >= minConfidence }
                .filter { label ->
                    val key = "${result.region.value}_${label.name}"
                    val lastTime = recentResults[key] ?: 0
                    val cooldown = if (currentMode == RecognitionMode.OFFLINE) resultCooldownMs / 2 else resultCooldownMs
                    if (now - lastTime >= cooldown) {
                        recentResults[key] = now
                        true
                    } else {
                        false
                    }
                }

            if (newLabels.isNotEmpty()) {
                filteredResults.add(
                    result.copy(labels = newLabels)
                )
            }
        }

        return filteredResults
    }

    fun generateBroadcastText(results: List<RecognitionResult>): String {
        if (results.isEmpty()) return ""

        val allDangerItems = mutableListOf<Pair<DangerObject, RecognitionRegion>>()
        val allNormalItems = mutableListOf<Pair<String, RecognitionRegion>>()

        for (result in results) {
            for (label in result.labels) {
                val danger = dangerObjects.find { danger ->
                    danger.keywords.any { keyword ->
                        label.name.contains(keyword, ignoreCase = true)
                    }
                }

                if (danger != null) {
                    val exists = allDangerItems.any {
                        it.first.name == danger.name && it.second == result.region
                    }
                    if (!exists) {
                        allDangerItems.add(danger to result.region)
                    }
                } else {
                    val exists = allNormalItems.any {
                        it.first == label.name && it.second == result.region
                    }
                    if (!exists) {
                        allNormalItems.add(label.name to result.region)
                    }
                }
            }
        }

        val sortedDangers = allDangerItems.sortedByDescending { it.first.level }

        val sb = StringBuilder()

        if (sortedDangers.isNotEmpty()) {
            val topDanger = sortedDangers.first()
            sb.append("注意，${topDanger.second.description}有${topDanger.first.name}")

            if (sortedDangers.size > 1) {
                val secondDanger = sortedDangers[1]
                sb.append("，${secondDanger.second.description}有${secondDanger.first.name}")
            }
        } else if (allNormalItems.isNotEmpty()) {
            val regionGroups = allNormalItems.groupBy { it.second }

            val centerItems = regionGroups[RecognitionRegion.CENTER]?.map { it.first }?.take(2)
            val leftItems = regionGroups[RecognitionRegion.LEFT]?.map { it.first }?.take(1)
            val rightItems = regionGroups[RecognitionRegion.RIGHT]?.map { it.first }?.take(1)

            val parts = mutableListOf<String>()

            if (!centerItems.isNullOrEmpty()) {
                parts.add("正前方：${centerItems.joinToString("、")}")
            }
            if (!leftItems.isNullOrEmpty()) {
                parts.add("左前方：${leftItems.joinToString("、")}")
            }
            if (!rightItems.isNullOrEmpty()) {
                parts.add("右前方：${rightItems.joinToString("、")}")
            }

            if (parts.isNotEmpty()) {
                sb.append(parts.joinToString("，"))
            }
        }

        return sb.toString()
    }

    fun setRecognitionInterval(intervalMs: Long) {
        recognitionIntervalMs = intervalMs
    }

    fun setOfflineInterval(intervalMs: Long) {
        offlineIntervalMs = intervalMs
    }

    fun setMinConfidence(confidence: Double) {
        minConfidence = confidence
    }

    fun setResultCooldown(cooldownMs: Long) {
        resultCooldownMs = cooldownMs
    }

    fun isRunning(): Boolean = isRecognizing.get()

    fun isOfflineReady(): Boolean = yoloInitialized

    fun clearCache() {
        recentResults.clear()
    }

    fun release() {
        stopRecognition()
        stopNetworkMonitor()
        clearCache()
        yoloManager.release()
    }
}
