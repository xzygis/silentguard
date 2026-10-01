package com.xzygis.silentguard.mail

import android.os.Build

object MailSubject {
    fun withDevicePrefix(subject: String): String =
        withDevicePrefix(subject, Build.MANUFACTURER, Build.MODEL)

    fun withDevicePrefix(subject: String, manufacturer: String, model: String): String {
        val device = listOf(manufacturer.trim(), model.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Android" }
        val prefix = "[$device]"
        val normalized = subject.trim()
        return if (normalized == prefix || normalized.startsWith("$prefix ")) {
            normalized
        } else {
            "$prefix $normalized"
        }
    }
}
