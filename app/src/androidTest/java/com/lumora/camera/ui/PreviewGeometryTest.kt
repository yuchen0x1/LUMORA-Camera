package com.lumora.camera.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewGeometryTest {
    @Test fun quarterTurnCropAndMirrorMapDistinctSceneLandmarksExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val preview = PreviewView(context).apply { layout(0, 0, 200, 160) }
            val texture = TextureView(context).apply {
                layout(7, 3, 247, 163)
                pivotX = 0f
                pivotY = 0f
                scaleX = 1.25f
                scaleY = 1.5f
                translationX = -37f
                translationY = -33f
            }
            val readback = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                // Clockwise 90 degrees inside a 240 x 160 texture, followed by a viewport crop.
                val correction = Matrix().apply {
                    setValues(floatArrayOf(0f, -1.5f, 240f, 2f / 3f, 0f, 0f, 0f, 0f, 1f))
                }
                fun mapLandmarks(): FloatArray {
                    texture.setTransform(correction)
                    val scene = floatArrayOf(75f, 28f, 30f, 44f, 15f, 8f)
                    texture.mapReadbackToViewport(readback, preview, 100, 80).mapPoints(scene)
                    return scene
                }
                assertArrayEquals(floatArrayOf(82.5f, 60f, 52.5f, 15f, 120f, 0f), mapLandmarks(), 0.001f)
                correction.postScale(-1f, 1f, 120f, 80f)
                assertArrayEquals(floatArrayOf(37.5f, 60f, 67.5f, 15f, 0f, 0f), mapLandmarks(), 0.001f)
            } finally {
                readback.recycle()
            }
        }
    }
}
