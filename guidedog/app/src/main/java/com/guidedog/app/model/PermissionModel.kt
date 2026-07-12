package com.guidedog.app.model

data class PermissionItem(
    val permission: String,
    val nameResId: Int,
    val descriptionResId: Int
)

data class PermissionResult(
    val permission: String,
    val isGranted: Boolean,
    val shouldShowRationale: Boolean
)
