package com.xzygis.silentguard.data

import org.junit.Assert.*
import org.junit.Test

class SmsIdentityTest {
    @Test
    fun `same message has stable identity while distinct messages remain distinct`() {
        val expected = SmsIdentity.key("sms", "+100", "hello", 123)
        assertEquals(expected, SmsIdentity.key("sms", "+100", "hello", 123))
        assertEquals(64, expected.length)
        assertNotEquals(expected, SmsIdentity.key("sms", "+100", "hello", 124))
        assertNotEquals(expected, SmsIdentity.key("sms", "+200", "hello", 123))
        assertNotEquals(expected, SmsIdentity.key("sms", "+100", "world", 123))
        assertNotEquals(SmsIdentity.key("a", "bc", "", 1), SmsIdentity.key("ab", "c", "", 1))
    }

    @Test
    fun `only matching recent notification text can select inbox content`() {
        assertTrue(SmsIdentity.matchesNotification("hello", "hello", 100_000, 100_000))
        assertTrue(SmsIdentity.matchesNotification("hello", "hello", 100_000, 220_000))
        assertFalse(SmsIdentity.matchesNotification("hello", "hello", 100_000, 220_001))
        assertFalse(SmsIdentity.matchesNotification("other", "hello", 100_000, 100_000))
        assertFalse(SmsIdentity.matchesNotification("hello", "", 100_000, 100_000))
        assertFalse(SmsIdentity.matchesNotification("", "", 100_000, 100_000))
    }
}
