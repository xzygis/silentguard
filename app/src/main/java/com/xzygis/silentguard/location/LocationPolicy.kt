package com.xzygis.silentguard.location

/** 与 Android API 无关的定位时效和调度规则。时间参数均使用开机后的单调时钟。 */
internal object LocationPolicy {
    const val MAX_FIX_AGE_MS = 60_000L
    const val FAILURE_RETRY_MS = 60_000L
    const val REQUEST_GRACE_MS = 60_000L

    fun isFresh(fixElapsedNanos: Long, nowElapsedNanos: Long): Boolean {
        if (fixElapsedNanos <= 0 || fixElapsedNanos > nowElapsedNanos) return false
        return nowElapsedNanos - fixElapsedNanos <= MAX_FIX_AGE_MS * 1_000_000L
    }

    fun nextDelayMillis(fixSucceeded: Boolean, intervalMinutes: Int): Long =
        if (fixSucceeded) intervalMinutes * 60_000L else FAILURE_RETRY_MS

    fun shouldAlert(
        now: Long,
        lastFix: Long,
        threshold: Long,
        nextAttempt: Long
    ): Boolean = now - lastFix >= threshold && now >= nextAttempt + REQUEST_GRACE_MS
}
