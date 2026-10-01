package com.xzygis.silentguard.mail

import java.util.Properties

object SmtpPropertiesBuilder {

    fun build(host: String, port: Int): Properties {
        return Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            put("mail.smtp.ssl.checkserveridentity", "true")

            if (port == 587) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            } else {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.port", port.toString())
            }
        }
    }
}
