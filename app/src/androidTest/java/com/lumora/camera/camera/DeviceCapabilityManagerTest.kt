package com.lumora.camera.camera

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceCapabilityManagerTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun exposedLensRangesAndIdsMatchTheDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
        val manager = DeviceCapabilityManager(context)
        val hardware = context.getSystemService(CameraManager::class.java)
        val infos = source.availableCameraInfos
        val lenses = infos.map(manager::lens)
        assertTrue(lenses.isNotEmpty())
        assertEquals(lenses.size, lenses.distinctBy { it.id }.size)
        infos.zip(lenses).forEach { (info, lens) ->
            val facts = manager.snapshot(info, lens, lenses, emptySet(), false)
            val c = hardware.getCameraCharacteristics(lens.id)
            assertEquals(c.physicalCameraIds.sorted(), facts.physicalCameraIds)
            val ev = info.exposureState
            val evRange = ev.exposureCompensationRange.takeIf { ev.isExposureCompensationSupported }
            assertEquals(evRange?.lower, facts.exposureCompensationRange?.min)
            assertEquals(evRange?.upper, facts.exposureCompensationRange?.max)
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            assertEquals(iso?.lower, facts.isoRange?.min)
            assertEquals(iso?.upper, facts.isoRange?.max)
            val jpeg = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            assertTrue(jpeg.all { (it.width to it.height) in facts.jpegSizes })
            val vendorControlled = manager.snapshot(info, lens, lenses, emptySet(), true)
            assertFalse(vendorControlled.supportsManualExposure)
            assertFalse(vendorControlled.supportsManualFocus)
        }
    }
}
