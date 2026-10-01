package com.xzygis.silentguard.data

import java.security.MessageDigest

object SmsIdentity {
    fun key(source: String, sender: String, body: String, timestamp: Long): String {
        val value = listOf(source, sender, body, timestamp.toString())
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun matchesNotification(body: String, preview: String, smsTime: Long, postedTime: Long): Boolean =
        preview.isNotBlank() && body == preview && kotlin.math.abs(smsTime - postedTime) <= 120_000
}
