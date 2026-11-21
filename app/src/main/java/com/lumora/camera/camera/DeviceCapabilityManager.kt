package com.lumora.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as R
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, androidx.camera.core.ExperimentalLensFacing::class)
class DeviceCapabilityManager(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun lens(info: CameraInfo): LensOption {
        val camera2 = Camera2CameraInfo.from(info)
        val focal = runCatching { camera2.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) }.getOrNull()
        val calibratedFocus = runCatching {
            if (camera2.getCameraCharacteristic(C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) == C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED)
                camera2.getCameraCharacteristic(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it.isFinite() && it > 0f }
            else null
        }.getOrNull()
        return LensOption(camera2.cameraId, facingName(info.lensFacing), focal?.filter { it.isFinite() && it > 0f }.orEmpty(), calibratedFocus)
    }

    internal fun rawAvailability(baseInfo: CameraInfo, sessionFailed: Boolean = false): RawAvailability {
        val supportsOutput = runCatching {
            ImageCapture.OUTPUT_FORMAT_RAW in ImageCapture.getImageCaptureCapabilities(baseInfo).supportedOutputFormats
        }.getOrDefault(false)
        return when {
            !supportsOutput -> RawAvailability.UNSUPPORTED
            sessionFailed -> RawAvailability.SESSION_UNAVAILABLE
            else -> RawAvailability.AVAILABLE
        }
    }

    fun snapshot(
        info: CameraInfo,
        lens: LensOption,
        lenses: List<LensOption>,
        extensions: Set<Int>,
        extensionActive: Boolean,
        rawAvailability: RawAvailability = rawAvailability(info)
    ): DeviceCapabilities {
        val c = runCatching { manager.getCameraCharacteristics(lens.id) }.getOrNull()
        fun <T> read(key: C.Key<T>): T? = runCatching { c?.get(key) }.getOrNull()
        val requestKeys = runCatching { c?.availableCaptureRequestKeys?.toSet() }.getOrNull().orEmpty()
        val capabilities = read(C.REQUEST_AVAILABLE_CAPABILITIES).orEmptyInts()
        val afModes = read(C.CONTROL_AF_AVAILABLE_MODES).orEmptyInts()
        val exposure = runCatching { info.exposureState }.getOrNull()?.takeIf { it.isExposureCompensationSupported }
        val zoom = runCatching { info.zoomState.value }.getOrNull()
        val minFocus = read(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it.isFinite() && it >= 0f }
        val map = read(C.SCALER_STREAM_CONFIGURATION_MAP)
        val jpeg = runCatching { map?.getOutputSizes(ImageFormat.JPEG)?.toList() }.getOrNull().orEmpty() +
            runCatching { map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() }.getOrNull().orEmpty()
        return DeviceCapabilities(
            cameraId = lens.id,
            lensFacing = lens.facing,
            availableCameras = lenses,
            physicalCameraIds = runCatching { c?.physicalCameraIds?.sorted() }.getOrNull().orEmpty(),
            focalLengthsMm = lens.focalLengthsMm,
            jpegSizes = jpeg.distinct().filter { it.width > 0 && it.height > 0 }
                .sortedByDescending { it.width.toLong() * it.height }.map { it.width to it.height },
            supportsFlash = runCatching { info.hasFlashUnit() }.getOrDefault(false),
            supportsAutoFocus = afModes.any { it == R.CONTROL_AF_MODE_AUTO || it == R.CONTROL_AF_MODE_MACRO ||
                it == R.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == R.CONTROL_AF_MODE_CONTINUOUS_VIDEO },
            zoomMin = zoom?.minZoomRatio,
            zoomMax = zoom?.maxZoomRatio,
            exposureCompensationRange = exposure?.exposureCompensationRange?.let { IntRangeValue(it.lower, it.upper) },
            exposureCompensationStep = exposure?.exposureCompensationStep?.toFloat()?.takeIf { it.isFinite() && it > 0f },
            isoRange = read(C.SENSOR_INFO_SENSITIVITY_RANGE)?.takeIf { it.lower > 0 }?.let { IntRangeValue(it.lower, it.upper) },
            exposureTimeRangeNs = read(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.takeIf { it.lower > 0 }?.let { LongRangeValue(it.lower, it.upper) },
            minimumFocusDistance = minFocus,
            focusDistanceInDiopters = read(C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) in listOf(
                C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE, C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED),
            manualSensorAvailable = !extensionActive && C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
                R.CONTROL_AE_MODE_OFF in read(C.CONTROL_AE_AVAILABLE_MODES).orEmptyInts() &&
                requestKeys.containsAll(listOf(R.CONTROL_AE_MODE, R.SENSOR_SENSITIVITY, R.SENSOR_EXPOSURE_TIME)),
            supportsManualFocus = !extensionActive && minFocus != null && minFocus > 0 &&
                R.CONTROL_AF_MODE_OFF in afModes && R.CONTROL_AF_MODE in requestKeys && R.LENS_FOCUS_DISTANCE in requestKeys,
            supportsAeLock = !extensionActive && read(C.CONTROL_AE_LOCK_AVAILABLE) == true && R.CONTROL_AE_LOCK in requestKeys,
            supportsAwbLock = !extensionActive && read(C.CONTROL_AWB_LOCK_AVAILABLE) == true && R.CONTROL_AWB_LOCK in requestKeys,
            whiteBalanceModes = if (extensionActive || R.CONTROL_AWB_MODE !in requestKeys) emptyList() else
                read(C.CONTROL_AWB_AVAILABLE_MODES).orEmptyInts().filter { it != R.CONTROL_AWB_MODE_OFF },
            supportsRaw = C.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
            rawAvailability = rawAvailability,
            supportsOpticalStabilization = R.LENS_OPTICAL_STABILIZATION_MODE_ON in read(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION).orEmptyInts(),
            supportsVideoStabilization = R.CONTROL_VIDEO_STABILIZATION_MODE_ON in read(C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES).orEmptyInts(),
            hardwareLevel = when (read(C.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                C.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                C.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                C.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                C.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                C.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> null
            },
            hdrExtensionAvailable = ExtensionMode.HDR in extensions,
            portraitExtensionAvailable = ExtensionMode.BOKEH in extensions,
            nightExtensionAvailable = ExtensionMode.NIGHT in extensions
        )
    }

    fun availableExtensions(manager: ExtensionsManager?, selector: CameraSelector): Set<Int> =
        listOf(ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.BOKEH).filter {
            runCatching { manager?.isExtensionAvailable(selector, it) == true }.getOrDefault(false)
        }.toSet()

    private fun facingName(value: Int): String = when (value) {
        CameraSelector.LENS_FACING_FRONT -> "FRONT"
        CameraSelector.LENS_FACING_BACK -> "BACK"
        CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun IntArray?.orEmptyInts(): IntArray = this ?: intArrayOf()
}
