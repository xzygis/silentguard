package com.xzygis.silentguard.mail

import android.content.Context
import android.util.Log
import com.xzygis.silentguard.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Properties
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

    suspend fun sendMail(subject: String, body: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val config = appConfig.configFlow.first()

                if (config.senderEmail.isBlank() || config.senderPassword.isBlank() || config.recipientEmail.isBlank()) {
                    Log.w(TAG, "邮件配置不完整，跳过发送")
                    return@withContext false
                }

                val properties = Properties().apply {
                    put("mail.smtp.host", config.smtpHost)
                    put("mail.smtp.port", config.smtpPort.toString())
                    put("mail.smtp.auth", "true")

                    if (config.smtpPort == 587) {
                        // STARTTLS（Outlook 等）
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                    } else {
                        // SSL（QQ、163、Gmail、飞书等，端口 465）
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                        put("mail.smtp.socketFactory.port", config.smtpPort.toString())
                    }
                }

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
                    setText(body)
                }

                Transport.send(message)
                Log.i(TAG, "邮件发送成功: $subject")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "邮件发送失败: ${e.message}", e)
                return@withContext false
            }
        }
    }
}
