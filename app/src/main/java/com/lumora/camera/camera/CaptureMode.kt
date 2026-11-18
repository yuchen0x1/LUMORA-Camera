package com.lumora.camera.camera

enum class CaptureMode(
    val label: String,
    val isHardwareBacked: Boolean,
    val isAssist: Boolean
) {
    AUTO("AUTO", true, false),
    PRO("PRO", true, false),
    HDR_EXTENSION("HDR 扩展", true, false),
    NIGHT_EXTENSION("夜景扩展", true, false),
    PORTRAIT_EXTENSION("人像扩展", true, false),
    NIGHT_ASSIST("夜景辅助", false, true),
    PORTRAIT_ASSIST("人像辅助", false, true),
    MACRO_ASSIST("微距对焦辅助", false, true),
    LANDSCAPE_PROFILE("风景配置", false, false),
    STREET_PROFILE("街拍配置", false, false),
    LONG_EXPOSURE_ASSIST("长曝光辅助", false, true)
}
