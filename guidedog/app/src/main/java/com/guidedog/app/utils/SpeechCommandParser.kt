package com.guidedog.app.utils

object SpeechCommandParser {

    enum class CommandType {
        NAVIGATE,
        RECOGNIZE,
        SETTINGS,
        SWITCH_CAMERA,
        TOGGLE_TORCH,
        CLOSE,
        STOP,
        SPEED_UP,
        SPEED_DOWN,
        REPEAT,
        HELP,
        DESTINATION,
        UNKNOWN
    }

    data class ParsedCommand(
        val type: CommandType,
        val rawText: String,
        val destination: String? = null
    )

    private val navigateKeywords = listOf(
        "导航", "带我去", "走到", "去", "前往", "走到", "指引", "去导航"
    )

    private val recognizeKeywords = listOf(
        "识别", "看看", "这是什么", "认一下", "看一眼", "识别模式"
    )

    private val settingsKeywords = listOf(
        "设置", "配置", "设定", "设置模式"
    )

    private val switchCameraKeywords = listOf(
        "切换摄像头", "换摄像头", "前置", "后置", "换镜头", "切换镜头"
    )

    private val toggleTorchKeywords = listOf(
        "开灯", "关灯", "闪光灯", "手电筒", "打开灯", "关闭灯"
    )

    private val closeKeywords = listOf(
        "退出", "关闭", "结束", "再见", "退出应用"
    )

    private val stopKeywords = listOf(
        "停止", "停下", "暂停", "别说了", "安静"
    )

    private val speedUpKeywords = listOf(
        "快一点", "加速", "说快点", "快点", "语速快"
    )

    private val speedDownKeywords = listOf(
        "慢一点", "减速", "说慢点", "慢点", "语速慢"
    )

    private val repeatKeywords = listOf(
        "再说一遍", "重复", "再说一次", "再说"
    )

    private val helpKeywords = listOf(
        "帮助", "怎么用", "使用说明", "指令", "有什么功能"
    )

    fun parse(text: String): ParsedCommand {
        if (text.isBlank()) {
            return ParsedCommand(CommandType.UNKNOWN, text)
        }

        val normalized = text.trim()

        val destination = extractDestination(normalized)
        if (destination != null) {
            return ParsedCommand(CommandType.DESTINATION, normalized, destination)
        }

        return when {
            matchesAny(normalized, navigateKeywords) -> ParsedCommand(CommandType.NAVIGATE, normalized)
            matchesAny(normalized, recognizeKeywords) -> ParsedCommand(CommandType.RECOGNIZE, normalized)
            matchesAny(normalized, settingsKeywords) -> ParsedCommand(CommandType.SETTINGS, normalized)
            matchesAny(normalized, switchCameraKeywords) -> ParsedCommand(CommandType.SWITCH_CAMERA, normalized)
            matchesAny(normalized, toggleTorchKeywords) -> ParsedCommand(CommandType.TOGGLE_TORCH, normalized)
            matchesAny(normalized, closeKeywords) -> ParsedCommand(CommandType.CLOSE, normalized)
            matchesAny(normalized, stopKeywords) -> ParsedCommand(CommandType.STOP, normalized)
            matchesAny(normalized, speedUpKeywords) -> ParsedCommand(CommandType.SPEED_UP, normalized)
            matchesAny(normalized, speedDownKeywords) -> ParsedCommand(CommandType.SPEED_DOWN, normalized)
            matchesAny(normalized, repeatKeywords) -> ParsedCommand(CommandType.REPEAT, normalized)
            matchesAny(normalized, helpKeywords) -> ParsedCommand(CommandType.HELP, normalized)
            else -> ParsedCommand(CommandType.UNKNOWN, normalized)
        }
    }

    private fun extractDestination(text: String): String? {
        for (keyword in navigateKeywords) {
            val idx = text.indexOf(keyword)
            if (idx >= 0) {
                val dest = text.substring(idx + keyword.length).trim()
                if (dest.isNotBlank()) {
                    return dest
                }
            }
        }
        return null
    }

    private fun matchesAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}
