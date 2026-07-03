package com.guidedog.app.controller

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guidedog.app.R
import com.guidedog.app.model.PermissionItem
import com.guidedog.app.model.PermissionResult

class PermissionController(private val context: Context) {

    val requiredPermissions: List<PermissionItem> = buildList {
        add(PermissionItem(Manifest.permission.CAMERA, R.string.permission_camera, R.string.permission_camera_desc))
        add(PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION, R.string.permission_location, R.string.permission_location_desc))
        add(PermissionItem(Manifest.permission.RECORD_AUDIO, R.string.permission_microphone, R.string.permission_microphone_desc))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS, R.string.permission_notification, R.string.permission_notification_desc))
        }
    }

    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun getAllUngrantedPermissions(): List<PermissionItem> {
        return requiredPermissions.filter { !isPermissionGranted(it.permission) }
    }

    fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    fun handlePermissionResult(
        permissions: Array<out String>,
        grantResults: IntArray,
        activity: Activity
    ): List<PermissionResult> {
        val results = mutableListOf<PermissionResult>()
        for (i in permissions.indices) {
            val isGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
            val shouldShowRationale = shouldShowRequestPermissionRationale(activity, permissions[i])
            results.add(PermissionResult(permissions[i], isGranted, shouldShowRationale))
        }
        return results
    }

    fun areAllPermissionsGranted(): Boolean {
        return requiredPermissions.all { isPermissionGranted(it.permission) }
    }

    companion object {
        const val REQUEST_CODE_REQUIRED_PERMISSIONS = 1001
    }
}
