package com.lumora.camera.update

import java.util.concurrent.TimeUnit

internal enum class UpdateFrequency(val label: String, val days: Long) {
    MANUAL("仅手动", 0), DAILY("每天", 1), WEEKLY("每周", 7)
}

internal object UpdateCheckPolicy {
    val failureBackoffMillis = TimeUnit.MINUTES.toMillis(15)

    fun isDue(frequency: UpdateFrequency, now: Long, lastSuccess: Long, lastFailure: Long): Boolean {
        if (frequency == UpdateFrequency.MANUAL) return false
        fun recent(timestamp: Long, interval: Long) = timestamp > 0 && timestamp <= now && now - timestamp < interval
        return !recent(lastSuccess, TimeUnit.DAYS.toMillis(frequency.days)) &&
            !recent(lastFailure, failureBackoffMillis)
    }
}
