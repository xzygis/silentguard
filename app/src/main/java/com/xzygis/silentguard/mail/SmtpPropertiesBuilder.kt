package com.xzygis.silentguard.mail

import java.util.Properties

object SmtpPropertiesBuilder {

    fun build(host: String, port: Int): Properties {
        return Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")

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
