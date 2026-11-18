package com.lumora.camera.camera

/** Null values leave the corresponding 3A algorithm under CameraX control. */
data class ManualControls(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val whiteBalanceMode: Int? = null,
    val focusDiopters: Float? = null,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false
) {
    fun forCamera(capabilities: DeviceCapabilities): ManualControls {
        require((iso == null) == (exposureTimeNs == null)) { "手动曝光需要同时指定 ISO 和曝光时间" }
        require(iso == null || capabilities.supportsManualExposure) { "当前相机不支持手动曝光" }
        require(!aeLock || (capabilities.supportsAeLock && iso == null)) { "当前设置不支持自动曝光锁定" }
        require(!awbLock || (capabilities.supportsAwbLock && whiteBalanceMode == null)) { "当前设置不支持自动白平衡锁定" }
        require(whiteBalanceMode == null || whiteBalanceMode in capabilities.whiteBalanceModes) { "当前相机不支持该白平衡模式" }
        require(focusDiopters == null || (capabilities.supportsManualFocus && focusDiopters.isFinite())) { "当前相机不支持该对焦距离" }
        return copy(
            iso = iso?.let(capabilities::clampIso),
            exposureTimeNs = exposureTimeNs?.let(capabilities::clampExposureTimeNs),
            focusDiopters = focusDiopters?.coerceIn(0f, requireNotNull(capabilities.minimumFocusDistance))
        )
    }
}

enum class FocusStatus { FOCUSING, FOCUSED, FAILED, TIMEOUT, UNAVAILABLE }
