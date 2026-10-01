package com.xzygis.silentguard.config

import org.junit.Assert.*
import org.junit.Test

class ConfigValidationTest {
    @Test
    fun `valid limits and optional empty email are accepted`() {
        assertTrue(ConfigValidation.errors(MonitorConfig()).isEmpty())
        for (config in listOf(
            MonitorConfig(smtpPort = 1, locationIntervalMinutes = 1, emailIntervalMinutes = 15, retentionDays = 7),
            MonitorConfig(smtpPort = 65535, locationIntervalMinutes = 60, emailIntervalMinutes = 1440, retentionDays = 3650),
            MonitorConfig(senderEmail = "sender@example.com", recipientEmail = "recipient@example.org")
        )) assertTrue(ConfigValidation.errors(config).isEmpty())
    }

    @Test
    fun `invalid intervals ports hosts and email are rejected`() {
        val invalid = listOf(
            MonitorConfig(smtpHost = ""), MonitorConfig(smtpHost = "smtp host"),
            MonitorConfig(smtpPort = 0), MonitorConfig(smtpPort = 65536),
            MonitorConfig(locationIntervalMinutes = 0), MonitorConfig(locationIntervalMinutes = Int.MAX_VALUE),
            MonitorConfig(emailIntervalMinutes = 14), MonitorConfig(emailIntervalMinutes = 1441),
            MonitorConfig(retentionDays = 6), MonitorConfig(retentionDays = 3651),
            MonitorConfig(senderEmail = "invalid"), MonitorConfig(recipientEmail = "a@b.com,c@d.com"),
            MonitorConfig(recipientEmail = "a@b.com\r\nBcc: c@d.com")
        )
        invalid.forEachIndexed { index, config ->
            assertFalse("Invalid fixture $index", ConfigValidation.errors(config).isEmpty())
        }
    }
}
