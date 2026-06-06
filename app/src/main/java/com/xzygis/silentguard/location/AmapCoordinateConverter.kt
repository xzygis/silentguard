package com.xzygis.silentguard.location

import android.content.Context
import android.util.Log
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng

object AmapCoordinateConverter {
    private const val TAG = "AmapCoordinateConverter"

    fun toAmapLatLng(context: Context, latitude: Double, longitude: Double): LatLng {
        val gpsLatLng = LatLng(latitude, longitude)
        return try {
            CoordinateConverter(context.applicationContext).apply {
                from(CoordinateConverter.CoordType.GPS)
                coord(gpsLatLng)
            }.convert()
        } catch (e: Exception) {
            Log.w(TAG, "GPS 坐标转换高德坐标失败，使用原始坐标: ${e.message}")
            gpsLatLng
        }
    }
}
