package com.xzygis.silentguard.config

object ConfigValidation {
    fun errors(config: MonitorConfig): List<String> = buildList {
        if (config.smtpHost.isBlank() || config.smtpHost.any { it.isWhitespace() }) add("SMTP 主机不能为空或包含空格")
        if (config.smtpPort !in 1..65535) add("SMTP 端口须为 1–65535")
        if (config.locationIntervalMinutes !in 1..60) add("定位间隔须为 1–60 分钟")
        if (config.emailIntervalMinutes !in 15..1440) add("邮件周期须为 15–1440 分钟")
        if (config.retentionDays !in 7..3650) add("保留期限须为 7–3650 天")
        val email = Regex("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$")
        if (config.senderEmail.isNotEmpty() && !email.matches(config.senderEmail)) add("发送邮箱格式无效")
        if (config.recipientEmail.isNotEmpty() && !email.matches(config.recipientEmail)) add("接收邮箱格式无效")
    }
}
