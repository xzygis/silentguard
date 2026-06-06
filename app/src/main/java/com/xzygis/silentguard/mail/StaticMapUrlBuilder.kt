package com.xzygis.silentguard.mail

import android.content.Context
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.location.AmapCoordinateConverter
import java.util.Locale

/**
 * 构建高德静态地图 URL，支持路径线和标记点
 * API 文档: https://lbs.amap.com/api/webservice/guide/api/staticmaps
 */
object StaticMapUrlBuilder {

    private const val BASE_URL = "https://restapi.amap.com/v3/staticmap"
    private const val MAX_SIZE = "750*400"
    private const val SCALE = 2 // 高清图

    /**
     * 根据位置事件列表生成静态地图 URL
     * @param events 位置事件列表（按时间升序排列）
     * @param apiKey 高德 Web 服务 API Key
     * @return 静态地图图片 URL，事件不足时返回 null
     */
    fun buildUrl(context: Context, events: List<MonitorEvent>, apiKey: String): String? {
        if (apiKey.isBlank()) return null

        val points = events.mapNotNull { event ->
            val latitude = event.latitude ?: return@mapNotNull null
            val longitude = event.longitude ?: return@mapNotNull null
            AmapCoordinateConverter.toAmapLatLng(context, latitude, longitude)
        }
        if (points.isEmpty()) return null

        val sb = StringBuilder(BASE_URL)
        sb.append("?key=$apiKey")
        sb.append("&size=$MAX_SIZE")
        sb.append("&scale=$SCALE")

        // 标记起点（绿色 S）和终点（红色 E）
        val markers = buildString {
            val first = points.first()
            append("mid,0x00CC33,S:${first.toUrlPoint()}")
            if (points.size > 1) {
                val last = points.last()
                append("|mid,0xFF3300,E:${last.toUrlPoint()}")
            }
        }
        sb.append("&markers=$markers")

        // 路径折线（蓝色实线）
        if (points.size >= 2) {
            val pathCoords = points.joinToString(";") { it.toUrlPoint() }
            // 格式: 线宽,颜色,透明度,填充颜色,填充透明度:坐标列表
            sb.append("&paths=5,0x2196F3,1,,:$pathCoords")
        }

        return sb.toString()
    }

    private fun com.amap.api.maps.model.LatLng.toUrlPoint(): String {
        return String.format(Locale.US, "%.6f,%.6f", longitude, latitude)
    }
}
