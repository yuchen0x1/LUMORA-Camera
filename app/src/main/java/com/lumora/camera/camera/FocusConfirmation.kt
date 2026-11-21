package com.lumora.camera.camera

/** A recent AF result is valid only while the requested focus state is unchanged. */
internal class FocusConfirmation(private val elapsedRealtime: () -> Long) {
    private var requestedDiopters: Float? = null
    private var confirmedAt: Long? = null

    fun controlsRequested(diopters: Float?): Boolean {
        if (diopters == requestedDiopters) return false
        requestedDiopters = diopters
        clear()
        return true
    }

    fun confirm() { confirmedAt = elapsedRealtime() }
    fun clear() { confirmedAt = null }
    fun isRecent(): Boolean = confirmedAt?.let { elapsedRealtime() - it in 0L until 1_200L } ?: false
}
