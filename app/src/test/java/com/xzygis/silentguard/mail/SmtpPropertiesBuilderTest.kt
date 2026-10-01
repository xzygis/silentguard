package com.xzygis.silentguard.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmtpPropertiesBuilderTest {

    @Test
    fun `all transports bound network waits and verify the server identity`() {
        listOf(465, 587, 2525).forEach { port ->
            val properties = SmtpPropertiesBuilder.build("smtp.example.com", port)
            assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
            assertEquals("30000", properties.getProperty("mail.smtp.timeout"))
            assertEquals("30000", properties.getProperty("mail.smtp.writetimeout"))
            assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        }
    }

    @Test
    fun `port 587 uses starttls`() {
        val properties = SmtpPropertiesBuilder.build("smtp.office365.com", 587)

        assertEquals("smtp.office365.com", properties.getProperty("mail.smtp.host"))
        assertEquals("587", properties.getProperty("mail.smtp.port"))
        assertEquals("true", properties.getProperty("mail.smtp.auth"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
        assertNull(properties.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun `port 465 uses ssl socket factory`() {
        val properties = SmtpPropertiesBuilder.build("smtp.qq.com", 465)

        assertEquals("smtp.qq.com", properties.getProperty("mail.smtp.host"))
        assertEquals("465", properties.getProperty("mail.smtp.port"))
        assertEquals("true", properties.getProperty("mail.smtp.auth"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.enable"))
        assertEquals("javax.net.ssl.SSLSocketFactory", properties.getProperty("mail.smtp.socketFactory.class"))
        assertEquals("465", properties.getProperty("mail.smtp.socketFactory.port"))
        assertNull(properties.getProperty("mail.smtp.starttls.enable"))
    }
}
