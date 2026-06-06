package com.xzygis.silentguard.mail

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class MailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MailWorker"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_BODY = "body"
        private const val KEY_IS_HTML = "is_html"

        fun enqueue(context: Context, subject: String, body: String, isHtml: Boolean = false) {
            val data = Data.Builder()
                .putString(KEY_SUBJECT, subject)
                .putString(KEY_BODY, body)
                .putBoolean(KEY_IS_HTML, isHtml)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MailWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val subject = inputData.getString(KEY_SUBJECT) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val isHtml = inputData.getBoolean(KEY_IS_HTML, false)

        val mailSender = MailSender(applicationContext)
        val success = mailSender.sendMail(subject, body, isHtml)

        return if (success) {
            Log.i(TAG, "邮件发送成功: $subject")
            Result.success()
        } else {
            if (runAttemptCount < 3) {
                Log.w(TAG, "邮件发送失败，将重试 (attempt $runAttemptCount): $subject")
                Result.retry()
            } else {
                Log.e(TAG, "邮件发送最终失败 (已重试3次): $subject")
                Result.failure()
            }
        }
    }
}
