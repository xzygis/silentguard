package com.xzygis.silentguard.mail

import android.content.Context
import android.util.Log
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.MailSendRecord
import com.xzygis.silentguard.data.MailSendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

class MailSender(private val context: Context) {

    companion object {
        private const val TAG = "MailSender"
    }

    private val appConfig = AppConfig(context)

    suspend fun sendMail(
        subject: String,
        body: String,
        isHtml: Boolean = false,
        retryCount: Int = 0
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val config = appConfig.configFlow.first()

                if (config.senderEmail.isBlank() || config.senderPassword.isBlank() || config.recipientEmail.isBlank()) {
                    Log.w(TAG, "邮件配置不完整，跳过发送")
                    recordMailResult(
                        subject = subject,
                        recipient = config.recipientEmail,
                        status = MailSendStatus.FAILED,
                        errorMessage = "邮件配置不完整",
                        retryCount = retryCount
                    )
                    return@withContext false
                }

                val properties = SmtpPropertiesBuilder.build(config.smtpHost, config.smtpPort)

                val session = Session.getInstance(properties, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(config.senderEmail, config.senderPassword)
                    }
                })
                session.debug = true

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(config.senderEmail))
                    setRecipient(Message.RecipientType.TO, InternetAddress(config.recipientEmail))
                    setSubject(subject)
                    if (isHtml) {
                        setContent(body, "text/html; charset=UTF-8")
                    } else {
                        setText(body)
                    }
                }

                Transport.send(message)
                Log.i(TAG, "邮件发送成功: $subject")
                recordMailResult(
                    subject = subject,
                    recipient = config.recipientEmail,
                    status = MailSendStatus.SENT,
                    retryCount = retryCount
                )
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "邮件发送失败: ${e.message}", e)
                recordMailResult(
                    subject = subject,
                    recipient = runCatching { appConfig.configFlow.first().recipientEmail }.getOrDefault(""),
                    status = if (retryCount > 0) MailSendStatus.RETRYING else MailSendStatus.FAILED,
                    errorMessage = e.message.orEmpty(),
                    retryCount = retryCount
                )
                return@withContext false
            }
        }
    }

    private suspend fun recordMailResult(
        subject: String,
        recipient: String,
        status: MailSendStatus,
        errorMessage: String = "",
        retryCount: Int = 0
    ) {
        runCatching {
            AppDatabase.getInstance(context).mailSendRecordDao().insert(
                MailSendRecord(
                    subject = subject,
                    recipient = recipient,
                    status = status,
                    errorMessage = errorMessage,
                    retryCount = retryCount
                )
            )
        }.onFailure { e ->
            Log.w(TAG, "邮件发送记录写入失败: ${e.message}")
        }
    }
}
