package com.xzygis.silentguard.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private val minute = 60_000L

    @Test
    fun `old cached positions cannot count as successful fixes`() {
        val now = 10 * minute * 1_000_000L
        assertTrue(LocationPolicy.isFresh(now - minute * 1_000_000L, now))
        assertFalse(LocationPolicy.isFresh(now - minute * 1_000_000L - 1, now))
        assertFalse(LocationPolicy.isFresh(1L, now))
    }

    @Test
    fun `missing or future timestamps are rejected`() {
        val now = 10 * minute * 1_000_000L
        assertFalse(LocationPolicy.isFresh(0L, now))
        assertFalse(LocationPolicy.isFresh(-1L, now))
        assertFalse(LocationPolicy.isFresh(now + 1, now))
    }

    @Test
    fun `failure retries in one minute even with a sixty minute night interval`() {
        assertEquals(minute, LocationPolicy.nextDelayMillis(false, 60))
        assertEquals(minute, LocationPolicy.nextDelayMillis(false, 5))
        assertEquals(60 * minute, LocationPolicy.nextDelayMillis(true, 60))
    }

    @Test
    fun `38 minute alert waits for planned night sample`() {
        assertFalse(LocationPolicy.shouldAlert(38 * minute, 0, 30 * minute, 60 * minute))
        assertFalse(LocationPolicy.shouldAlert(60 * minute, 0, 30 * minute, 60 * minute))
        assertTrue(LocationPolicy.shouldAlert(61 * minute, 0, 30 * minute, 60 * minute))
    }

    @Test
    fun `failed retry does not defer an overdue alert`() {
        // 失败时调用方清除正常采样等待时间，连续重试不掩盖故障。
        assertTrue(LocationPolicy.shouldAlert(38 * minute, 0, 30 * minute, 0))
        assertFalse(LocationPolicy.shouldAlert(29 * minute, 0, 30 * minute, 0))
    }

    @Test
    fun `fresh stationary fix prevents alert even without a new track point`() {
        assertFalse(LocationPolicy.shouldAlert(61 * minute, 60 * minute, 30 * minute, 0))
    }
}
