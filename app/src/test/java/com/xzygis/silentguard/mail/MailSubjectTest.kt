package com.xzygis.silentguard.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class MailSubjectTest {
    @Test
    fun `adds manufacturer and model to an unprefixed subject`() {
        assertEquals(
            "[HUAWEI VOG-AL10] [短信] 来自: 10086",
            MailSubject.withDevicePrefix("[短信] 来自: 10086", "HUAWEI", "VOG-AL10")
        )
    }

    @Test
    fun `does not duplicate an existing device prefix`() {
        assertEquals(
            "[HUAWEI VOG-AL10] SOS 一键求助",
            MailSubject.withDevicePrefix(
                "[HUAWEI VOG-AL10] SOS 一键求助",
                "HUAWEI",
                "VOG-AL10"
            )
        )
    }

    @Test
    fun `normalizes whitespace and handles missing device values`() {
        assertEquals(
            "[Pixel 9] 测试邮件",
            MailSubject.withDevicePrefix("  测试邮件  ", "", " Pixel 9 ")
        )
        assertEquals(
            "[Android] 测试邮件",
            MailSubject.withDevicePrefix("测试邮件", " ", "")
        )
    }
}
