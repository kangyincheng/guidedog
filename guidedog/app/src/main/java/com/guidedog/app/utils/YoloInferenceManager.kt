package com.guidedog.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class YoloDetection(
    val className: String,
    val chineseName: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val region: RecognitionRegion,
    val dangerLevel: Int
)

class YoloInferenceManager private constructor(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val isModelLoaded = AtomicBoolean(false)

    private val inputSize = 640
    private var inputName: String = "images"
    private var outputName: String = "output0"

    private val cocoClasses = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val classChineseMap = mapOf(
        "person" to "行人",
        "bicycle" to "自行车",
        "car" to "汽车",
        "motorcycle" to "摩托车",
        "bus" to "公交车",
        "truck" to "卡车",
        "boat" to "船只",
        "traffic light" to "红绿灯",
        "fire hydrant" to "消防栓",
        "stop sign" to "停车标志",
        "parking meter" to "停车计时器",
        "bench" to "长椅",
        "bird" to "鸟类",
        "cat" to "猫",
        "dog" to "狗",
        "horse" to "马",
        "cow" to "牛",
        "elephant" to "大象",
        "bear" to "熊",
        "chair" to "椅子",
        "couch" to "沙发",
        "potted plant" to "盆栽",
        "bed" to "床",
        "dining table" to "桌子",
        "toilet" to "马桶",
        "tv" to "电视",
        "laptop" to "笔记本电脑",
        "cell phone" to "手机",
        "backpack" to "背包",
        "umbrella" to "雨伞",
        "suitcase" to "行李箱",
        "bottle" to "瓶子",
        "cup" to "杯子",
        "bowl" to "碗",
        "vase" to "花瓶",
        "scissors" to "剪刀",
        "teddy bear" to "玩具熊",
        "clock" to "时钟",
        "book" to "书籍",
        "refrigerator" to "冰箱",
        "microwave" to "微波炉",
        "oven" to "烤箱",
        "sink" to "水槽",
        "keyboard" to "键盘",
        "mouse" to "鼠标",
        "remote" to "遥控器"
    )

    private val dangerClassMap = mapOf(
        "person" to DangerLevel(1, "行人"),
        "bicycle" to DangerLevel(2, "自行车"),
        "car" to DangerLevel(3, "车辆"),
        "motorcycle" to DangerLevel(3, "摩托车"),
        "bus" to DangerLevel(3, "公交车"),
        "truck" to DangerLevel(3, "卡车"),
        "boat" to DangerLevel(2, "船只"),
        "traffic light" to DangerLevel(2, "红绿灯"),
        "fire hydrant" to DangerLevel(2, "消防栓"),
        "stop sign" to DangerLevel(2, "停车标志"),
        "bench" to DangerLevel(1, "长椅"),
        "chair" to DangerLevel(1, "椅子"),
        "couch" to DangerLevel(1, "沙发"),
        "dining table" to DangerLevel(1, "桌子"),
        "bed" to DangerLevel(1, "床"),
        "toilet" to DangerLevel(1, "马桶"),
        "potted plant" to DangerLevel(1, "盆栽"),
        "tv" to DangerLevel(1, "电视"),
        "laptop" to DangerLevel(1, "笔记本电脑"),
        "suitcase" to DangerLevel(1, "行李箱"),
        "backpack" to DangerLevel(1, "背包"),
        "umbrella" to DangerLevel(1, "雨伞"),
        "dog" to DangerLevel(2, "狗"),
        "cat" to DangerLevel(1, "猫"),
        "horse" to DangerLevel(2, "马"),
        "cow" to DangerLevel(2, "牛"),
        "elephant" to DangerLevel(2, "大象"),
        "bear" to DangerLevel(3, "熊")
    )

    data class DangerLevel(val level: Int, val displayName: String)

    companion object {
        private const val TAG = "YoloInferenceManager"
        private const val MODEL_FILE = "yolov8_tiny.onnx"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f

        @Volatile
        private var INSTANCE: YoloInferenceManager? = null

        fun getInstance(context: Context): YoloInferenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: YoloInferenceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun init(): Boolean {
        if (isModelLoaded.get()) return true
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = loadModelFile() ?: return false
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)

            val inputInfo = ortSession!!.inputNames
            if (inputInfo.isNotEmpty()) {
                inputName = inputInfo.first()
            }
            val outputInfo = ortSession!!.outputNames
            if (outputInfo.isNotEmpty()) {
                outputName = outputInfo.first()
            }

            isModelLoaded.set(true)
            Log.i(TAG, "YOLOv8模型加载成功, input=$inputName, output=$outputName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "YOLOv8模型加载失败", e)
            isModelLoaded.set(false)
            false
        }
    }

    private fun loadModelFile(): ByteArray? {
        return try {
            context.assets.open(MODEL_FILE).use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var read: Int
                while (input.read(chunk).also { read = it } != -1) {
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型文件未找到: $MODEL_FILE，请将YOLOv8-Tiny ONNX模型放入assets目录", e)
            null
        }
    }

    fun isReady(): Boolean = isModelLoaded.get() && ortSession != null

    fun detect(nv21Bytes: ByteArray, width: Int, height: Int): List<YoloDetection> {
        if (!isReady()) return emptyList()
        return try {
            val bitmap = nv21ToBitmap(nv21Bytes, width, height)
            val (resizedBitmap, scaleX, scaleY) = preprocess(bitmap)
            bitmap.recycle()
            val detections = infer(resizedBitmap)
            resizedBitmap.recycle()
            val scaledDetections = detections.map { det ->
                det.copy(
                    x1 = det.x1 * scaleX,
                    y1 = det.y1 * scaleY,
                    x2 = det.x2 * scaleX,
                    y2 = det.y2 * scaleY
                )
            }
            val withRegion = scaledDetections.map { det ->
                val centerX = (det.x1 + det.x2) / 2f
                val region = computeRegion(centerX, width.toFloat())
                val boxArea = (det.x2 - det.x1) * (det.y2 - det.y1)
                val frameArea = width.toFloat() * height.toFloat()
                val areaRatio = boxArea / frameArea
                val adjustedDanger = adjustDangerLevel(det.dangerLevel, areaRatio)
                det.copy(region = region, dangerLevel = adjustedDanger)
            }
            withRegion
        } catch (e: Exception) {
            Log.e(TAG, "检测失败", e)
            emptyList()
        }
    }

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val jpegBytes = out.toByteArray()
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
    }

    private fun preprocess(bitmap: Bitmap): Triple<Bitmap, Float, Float> {
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height

        val scale = minOf(inputSize.toFloat() / srcWidth, inputSize.toFloat() / srcHeight)
        val newWidth = (srcWidth * scale).toInt()
        val newHeight = (srcHeight * scale).toInt()

        val padded = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.BLACK)
        val scaledSrc = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        canvas.drawBitmap(scaledSrc, 0f, 0f, null)

        if (scaledSrc != bitmap) {
            scaledSrc.recycle()
        }

        val invScale = 1f / scale
        return Triple(padded, invScale, invScale)
    }

    private fun infer(bitmap: Bitmap): List<YoloDetection> {
        val floatArray = bitmapToFloatArray(bitmap)

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, FloatBuffer.wrap(floatArray), shape)

        val rawDetections = mutableListOf<RawDetection>()

        try {
            val inputs = mapOf(inputName to inputTensor)
            val results = ortSession!!.run(inputs)
            val outputTensor = results.get(0)
            val outShape = outputTensor.info.shape

            val dim1 = if (outShape.size >= 2) outShape[1].toInt() else 0
            val dim2 = if (outShape.size >= 3) outShape[2].toInt() else 0

            if (outShape.size == 3 && dim1 == 84 && dim2 > 84) {
                @Suppress("UNCHECKED_CAST")
                val output3d = outputTensor.value as Array<Array<FloatArray>>
                val batch = output3d[0]

                for (anchor in 0 until dim2) {
                    val cx = batch[0][anchor]
                    val cy = batch[1][anchor]
                    val w = batch[2][anchor]
                    val h = batch[3][anchor]

                    var maxConf = 0f
                    var maxClassIdx = -1
                    for (cls in 0 until 80) {
                        val conf = batch[4 + cls][anchor]
                        if (conf > maxConf) {
                            maxConf = conf
                            maxClassIdx = cls
                        }
                    }

                    if (maxConf < CONFIDENCE_THRESHOLD || maxClassIdx < 0 || maxClassIdx >= cocoClasses.size) {
                        continue
                    }

                    val className = cocoClasses[maxClassIdx]
                    val danger = dangerClassMap[className]
                    val displayName = danger?.displayName ?: classChineseMap[className] ?: className

                    rawDetections.add(
                        RawDetection(
                            className = className,
                            chineseName = displayName,
                            confidence = maxConf,
                            x1 = cx - w / 2f, y1 = cy - h / 2f,
                            x2 = cx + w / 2f, y2 = cy + h / 2f,
                            dangerLevel = danger?.level ?: 0
                        )
                    )
                }
            } else if (outShape.size == 3 && dim1 > 84 && dim2 == 84) {
                @Suppress("UNCHECKED_CAST")
                val output3d = outputTensor.value as Array<Array<FloatArray>>
                val batch = output3d[0]

                for (anchor in batch.indices) {
                    val row = batch[anchor]
                    if (row.size < 84) continue

                    var maxConf = 0f
                    var maxClassIdx = -1
                    for (cls in 4 until minOf(row.size, 84)) {
                        if (row[cls] > maxConf) {
                            maxConf = row[cls]
                            maxClassIdx = cls - 4
                        }
                    }

                    if (maxConf < CONFIDENCE_THRESHOLD || maxClassIdx < 0 || maxClassIdx >= cocoClasses.size) {
                        continue
                    }

                    val className = cocoClasses[maxClassIdx]
                    val danger = dangerClassMap[className]
                    val displayName = danger?.displayName ?: classChineseMap[className] ?: className

                    val cx = row[0]; val cy = row[1]; val w = row[2]; val h = row[3]
                    rawDetections.add(
                        RawDetection(
                            className = className,
                            chineseName = displayName,
                            confidence = maxConf,
                            x1 = cx - w / 2f, y1 = cy - h / 2f,
                            x2 = cx + w / 2f, y2 = cy + h / 2f,
                            dangerLevel = danger?.level ?: 0
                        )
                    )
                }
            } else {
                Log.w(TAG, "未知输出格式: shape=${outShape.toList()}")
            }

            outputTensor.close()
            results.close()
        } catch (e: Exception) {
            Log.e(TAG, "推理异常", e)
        } finally {
            inputTensor.close()
        }

        return applyNMS(rawDetections)
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val floatArray = FloatArray(3 * width * height)
        val pixelsCount = pixels.size
        for (i in 0 until pixelsCount) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f       // R
            floatArray[pixelsCount + i] = ((pixel shr 8) and 0xFF) / 255.0f  // G
            floatArray[2 * pixelsCount + i] = (pixel and 0xFF) / 255.0f       // B
        }
        return floatArray
    }

    private data class RawDetection(
        val className: String,
        val chineseName: String,
        val confidence: Float,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val dangerLevel: Int
    )

    private fun applyNMS(detections: List<RawDetection>): List<YoloDetection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<RawDetection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            selected.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val iou = computeIoU(sorted[i], sorted[j])
                if (iou > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }

        return selected.map { det ->
            YoloDetection(
                className = det.className,
                chineseName = det.chineseName,
                confidence = det.confidence,
                x1 = det.x1, y1 = det.y1, x2 = det.x2, y2 = det.y2,
                region = RecognitionRegion.CENTER,
                dangerLevel = det.dangerLevel
            )
        }
    }

    private fun computeIoU(a: RawDetection, b: RawDetection): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)

        val interW = maxOf(0f, interX2 - interX1)
        val interH = maxOf(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)

        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    private fun computeRegion(centerX: Float, frameWidth: Float): RecognitionRegion {
        val ratio = centerX / frameWidth
        return when {
            ratio < 0.33f -> RecognitionRegion.LEFT
            ratio > 0.67f -> RecognitionRegion.RIGHT
            else -> RecognitionRegion.CENTER
        }
    }

    private fun adjustDangerLevel(baseLevel: Int, areaRatio: Float): Int {
        if (baseLevel <= 0) return 0
        return when {
            areaRatio > 0.25f -> baseLevel + 1
            areaRatio > 0.10f -> baseLevel
            else -> maxOf(0, baseLevel - 1)
        }
    }

    fun generateBroadcastText(detections: List<YoloDetection>): String {
        if (detections.isEmpty()) return ""

        val dangerDetections = detections.filter { it.dangerLevel >= 2 }
            .sortedByDescending { it.dangerLevel }
        val normalDetections = detections.filter { it.dangerLevel < 2 }

        val sb = StringBuilder()

        if (dangerDetections.isNotEmpty()) {
            sb.append("注意")
            val topDangers = dangerDetections.take(3)
            for ((index, det) in topDangers.withIndex()) {
                if (index > 0) sb.append("，")
                sb.append("${det.region.description}有${det.chineseName}")
            }
        } else if (normalDetections.isNotEmpty()) {
            val regionGroups = normalDetections.groupBy { it.region }

            val centerItems = regionGroups[RecognitionRegion.CENTER]
                ?.sortedByDescending { it.confidence }
                ?.take(2)
                ?.map { it.chineseName }
            val leftItems = regionGroups[RecognitionRegion.LEFT]
                ?.sortedByDescending { it.confidence }
                ?.take(1)
                ?.map { it.chineseName }
            val rightItems = regionGroups[RecognitionRegion.RIGHT]
                ?.sortedByDescending { it.confidence }
                ?.take(1)
                ?.map { it.chineseName }

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

    fun release() {
        try {
            ortSession?.close()
            isModelLoaded.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "释放YOLO资源失败", e)
        }
    }
}
