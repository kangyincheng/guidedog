package com.guidedog.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

enum class RecognitionMode {
    ONLINE,
    OFFLINE
}

class NetworkMonitor private constructor(private val context: Context) {

    private val TAG = "NetworkMonitor"
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false

    @Volatile
    private var currentOnline = true

    @Volatile
    private var currentMode = RecognitionMode.ONLINE

    private val listeners = CopyOnWriteArrayList<(RecognitionMode) -> Unit>()

    companion object {
        @Volatile
        private var INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        updateInitialNetworkState()

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "网络已连接")
                onNetworkChanged(true)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "网络已断开")
                onNetworkChanged(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (hasInternet && isValidated) {
                    onNetworkChanged(true)
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
        }
    }

    private fun updateInitialNetworkState() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val hasInternet = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            onNetworkChanged(hasInternet)
        } catch (e: Exception) {
            Log.e(TAG, "获取初始网络状态失败", e)
            onNetworkChanged(false)
        }
    }

    private fun onNetworkChanged(isOnline: Boolean) {
        val newMode = if (isOnline) RecognitionMode.ONLINE else RecognitionMode.OFFLINE
        val modeChanged = newMode != currentMode

        currentOnline = isOnline
        currentMode = newMode

        if (modeChanged) {
            Log.i(TAG, "识别模式切换: ${if (isOnline) "在线(阿里云)" else "离线(YOLO)"}")
            listeners.forEach { listener ->
                try {
                    listener.invoke(newMode)
                } catch (e: Exception) {
                    Log.e(TAG, "网络监听回调异常", e)
                }
            }
        }
    }

    fun isOnline(): Boolean = currentOnline

    fun getCurrentMode(): RecognitionMode = currentMode

    fun addOnModeChangedListener(listener: (RecognitionMode) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeOnModeChangedListener(listener: (RecognitionMode) -> Unit) {
        listeners.remove(listener)
    }

    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注销网络回调失败", e)
        }
        networkCallback = null
        listeners.clear()
    }
}
