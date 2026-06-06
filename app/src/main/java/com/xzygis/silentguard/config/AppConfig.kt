package com.xzygis.silentguard.config

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

data class MonitorConfig(
    val smtpHost: String = "smtp.feishu.cn",
    val smtpPort: Int = 465,
    val senderEmail: String = "",
    val senderPassword: String = "",
    val recipientEmail: String = "",
    val locationIntervalMinutes: Int = 15,
    val isMonitoringEnabled: Boolean = false
)

class AppConfig(private val context: Context) {

    private object Keys {
        val SMTP_HOST = stringPreferencesKey("smtp_host")
        val SMTP_PORT = intPreferencesKey("smtp_port")
        val SENDER_EMAIL = stringPreferencesKey("sender_email")
        val RECIPIENT_EMAIL = stringPreferencesKey("recipient_email")
        val LOCATION_INTERVAL = intPreferencesKey("location_interval_minutes")
        val IS_MONITORING_ENABLED = booleanPreferencesKey("is_monitoring_enabled")
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_config",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val configFlow: Flow<MonitorConfig> = context.dataStore.data.map { prefs ->
        MonitorConfig(
            smtpHost = prefs[Keys.SMTP_HOST] ?: "smtp.feishu.cn",
            smtpPort = prefs[Keys.SMTP_PORT] ?: 465,
            senderEmail = prefs[Keys.SENDER_EMAIL] ?: "",
            senderPassword = encryptedPrefs.getString("sender_password", "") ?: "",
            recipientEmail = prefs[Keys.RECIPIENT_EMAIL] ?: "",
            locationIntervalMinutes = prefs[Keys.LOCATION_INTERVAL] ?: 15,
            isMonitoringEnabled = prefs[Keys.IS_MONITORING_ENABLED] ?: false
        )
    }

    suspend fun getConfig(): MonitorConfig {
        return configFlow.first()
    }

    suspend fun saveConfig(config: MonitorConfig) {
        // 密码加密存储
        encryptedPrefs.edit().putString("sender_password", config.senderPassword).apply()

        // 其他配置存 DataStore
        context.dataStore.edit { prefs ->
            prefs[Keys.SMTP_HOST] = config.smtpHost
            prefs[Keys.SMTP_PORT] = config.smtpPort
            prefs[Keys.SENDER_EMAIL] = config.senderEmail
            prefs[Keys.RECIPIENT_EMAIL] = config.recipientEmail
            prefs[Keys.LOCATION_INTERVAL] = config.locationIntervalMinutes
            prefs[Keys.IS_MONITORING_ENABLED] = config.isMonitoringEnabled
        }
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_MONITORING_ENABLED] = enabled
        }
    }
}
