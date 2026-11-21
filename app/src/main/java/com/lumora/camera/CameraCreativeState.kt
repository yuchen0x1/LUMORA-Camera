package com.lumora.camera

import androidx.compose.runtime.saveable.listSaver
import com.lumora.camera.camera.ManualControls

internal val ManualControlsSaver = listSaver<ManualControls, Any>(
    save = {
        listOf(it.iso ?: -1, it.exposureTimeNs ?: -1L, it.whiteBalanceMode ?: -1,
            it.focusDiopters ?: -1f, it.aeLock, it.awbLock)
    },
    restore = {
        ManualControls(
            (it[0] as Int).takeIf { value -> value >= 0 },
            (it[1] as Long).takeIf { value -> value >= 0 },
            (it[2] as Int).takeIf { value -> value >= 0 },
            (it[3] as Float).takeIf { value -> value >= 0 },
            it[4] as Boolean, it[5] as Boolean
        )
    }
)
