package com.guidedog.app.utils

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AliyunApiUtils {

    private const val TAG = "AliyunApiUtils"
    private const val ENDPOINT = "imagerecog.cn-shanghai.aliyuncs.com"
    private const val API_VERSION = "2019-09-30"
    private const val SIGNATURE_METHOD = "HMAC-SHA1"
    private const val SIGNATURE_VERSION = "1.0"

    private var accessKeyId: String = ""
    private var accessKeySecret: String = ""

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun init(accessKeyId: String, accessKeySecret: String) {
        this.accessKeyId = accessKeyId
        this.accessKeySecret = accessKeySecret
    }

    suspend fun taggingImage(imageUrl: String): Result<List<ImageLabel>> {
        return withContext(Dispatchers.IO) {
            try {
                val params = mutableMapOf<String, String>()
                params["Action"] = "TaggingImage"
                params["ImageURL"] = imageUrl

                val response = invokeApi(params)
                val labels = parseTaggingResponse(response)
                Result.success(labels)
            } catch (e: Exception) {
                Log.e(TAG, "图像打标失败", e)
                Result.failure(e)
            }
        }
    }

    suspend fun taggingImageWithBytes(jpegBytes: ByteArray): Result<List<ImageLabel>> {
        return withContext(Dispatchers.IO) {
            try {
                val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val imageUrl = "data:image/jpeg;base64,$base64Image"

                val params = mutableMapOf<String, String>()
                params["Action"] = "TaggingImage"
                params["ImageURL"] = imageUrl

                val response = invokeApi(params)
                val labels = parseTaggingResponse(response)
                Result.success(labels)
            } catch (e: Exception) {
                Log.e(TAG, "图像打标（字节）失败", e)
                Result.failure(e)
            }
        }
    }

    private fun parseTaggingResponse(response: String): List<ImageLabel> {
        val json = JSONObject(response)

        if (json.has("Data")) {
            val data = json.getJSONObject("Data")
            val labelsArray = data.optJSONArray("Tags")
                ?: data.optJSONArray("Elements")
                ?: return emptyList()

            val labels = mutableListOf<ImageLabel>()
            for (i in 0 until labelsArray.length()) {
                val labelObj = labelsArray.getJSONObject(i)
                val name = labelObj.optString("TagName")
                    ?: labelObj.optString("LabelName")
                    ?: labelObj.optString("Value", "")
                val confidence = labelObj.optDouble("TagConfidence")
                    ?: labelObj.optDouble("Confidence", 0.0)
                if (name.isNotEmpty()) {
                    labels.add(ImageLabel(name, confidence))
                }
            }
            return labels
        } else if (json.has("Code")) {
            val code = json.getString("Code")
            val message = json.optString("Message", "未知错误")
            throw Exception("API错误: $code - $message")
        }
        return emptyList()
    }

    private suspend fun invokeApi(params: Map<String, String>): String {
        val allParams = mutableMapOf<String, String>()
        allParams.putAll(params)

        allParams["Format"] = "JSON"
        allParams["Version"] = API_VERSION
        allParams["AccessKeyId"] = accessKeyId
        allParams["SignatureMethod"] = SIGNATURE_METHOD
        allParams["SignatureVersion"] = SIGNATURE_VERSION
        allParams["SignatureNonce"] = generateNonce()
        allParams["Timestamp"] = generateTimestamp()
        allParams["Signature"] = generateSignature(allParams)

        val formBody = FormBody.Builder()
        for ((key, value) in allParams) {
            formBody.add(key, value)
        }

        val request = Request.Builder()
            .url("https://$ENDPOINT/")
            .post(formBody.build())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP请求失败: ${response.code}")
                }
                response.body?.string() ?: ""
            }
        }
    }

    private fun generateSignature(params: Map<String, String>): String {
        val sortedKeys = params.keys.sorted()
        
        val queryStringBuilder = StringBuilder()
        for (i in sortedKeys.indices) {
            if (i > 0) queryStringBuilder.append("&")
            val key = sortedKeys[i]
            queryStringBuilder.append(percentEncode(key))
            queryStringBuilder.append("=")
            queryStringBuilder.append(percentEncode(params[key] ?: ""))
        }

        val stringToSign = "POST&%2F&${percentEncode(queryStringBuilder.toString())}"

        return hmacSha1(accessKeySecret + "&", stringToSign)
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun hmacSha1(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1")
        mac.init(secretKeySpec)
        val result = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    private fun generateNonce(): String {
        return System.currentTimeMillis().toString() + (Math.random() * 10000).toInt()
    }

    private fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    data class ImageLabel(
        val name: String,
        val confidence: Double
    )
}
