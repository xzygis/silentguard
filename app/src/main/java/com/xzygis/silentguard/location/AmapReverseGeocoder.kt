package com.xzygis.silentguard.location

import android.content.Context
import android.util.Log
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object AmapReverseGeocoder {
    private const val TAG = "AmapReverseGeocoder"
    private const val ENDPOINT = "https://restapi.amap.com/v3/geocode/regeo"
    private const val ADDRESS_PREFIX = "地址: "

    suspend fun resolveAddress(
        context: Context,
        apiKey: String,
        latitude: Double,
        longitude: Double
    ): String? {
        if (apiKey.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val amapLatLng = toAmapLatLng(context, latitude, longitude)
                val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
                val location = String.format(
                    Locale.US,
                    "%.6f,%.6f",
                    amapLatLng.longitude,
                    amapLatLng.latitude
                )
                val url = URL("$ENDPOINT?key=$encodedKey&location=$location&extensions=base&output=JSON")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }

                connection.inputStream.bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    if (json.optString("status") != "1") {
                        Log.w(TAG, "逆地理编码失败: ${json.optString("info")}")
                        return@withContext null
                    }
                    json.optJSONObject("regeocode")
                        ?.optString("formatted_address")
                        ?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "逆地理编码异常: ${e.message}")
                null
            }
        }
    }

    fun extractAddress(detail: String): String? {
        return detail.lineSequence()
            .firstOrNull { it.startsWith(ADDRESS_PREFIX) }
            ?.removePrefix(ADDRESS_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun formatSummary(address: String?, latitude: Double, longitude: Double): String {
        return address ?: "%.4f, %.4f".format(latitude, longitude)
    }

    private fun toAmapLatLng(context: Context, latitude: Double, longitude: Double): LatLng {
        val gpsLatLng = LatLng(latitude, longitude)
        return try {
            CoordinateConverter(context.applicationContext).apply {
                from(CoordinateConverter.CoordType.GPS)
                coord(gpsLatLng)
            }.convert()
        } catch (e: Exception) {
            Log.w(TAG, "坐标转换失败，使用原始坐标: ${e.message}")
            gpsLatLng
        }
    }
}
