package com.xzygis.silentguard.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 统一的设备定位入口，供前台地图页与后台守护服务共用。
 *
 * 优先使用 Google Play Services 的 FusedLocationProvider；当 GMS 不可用
 * （如华为等无 GMS 机型）、超时或返回过期结果时，降级到系统 GPS / 网络定位。
 * 所有途径只接受一分钟内的位置，避免把陈旧缓存当成新的定位成功。
 */
object DeviceLocationProvider {

    private const val TAG = "DeviceLocation"
    private const val GMS_LOCATION_TIMEOUT_MS = 10_000L
    private const val SYSTEM_LOCATION_TIMEOUT_MS = 45_000L

    /**
     * 获取当前设备位置。调用方需自行确保已持有定位权限。
     *
     * @param highAccuracy 为 true 时使用高精度定位，否则使用均衡功耗模式
     * @return 定位结果，全部途径均失败时返回 null
     */
    suspend fun getCurrentLocation(context: Context, highAccuracy: Boolean = false): Location? {
        getGmsLocation(context, highAccuracy)?.let { return it }
        return getSystemLocation(context)
    }

    @SuppressLint("MissingPermission") // 调用方负责在获取定位权限后调用，内部亦已捕获 SecurityException
    private suspend fun getGmsLocation(context: Context, highAccuracy: Boolean): Location? {
        val gmsAvailable = try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }

        if (!gmsAvailable) {
            Log.w(TAG, "Google Play Services 不可用，改用系统定位")
            return null
        }

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellation = CancellationTokenSource()
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            withTimeoutOrNull(GMS_LOCATION_TIMEOUT_MS) {
                fusedClient.getCurrentLocation(priority, cancellation.token).await()
                    ?.takeIf(::isFresh)
            }.also {
                if (it == null) Log.w(TAG, "GMS 未返回新鲜位置，改用系统定位")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GMS 定位失败，改用系统定位: ${e.message}")
            null
        } finally {
            cancellation.cancel()
        }
    }

    private fun isFresh(location: Location): Boolean =
        LocationPolicy.isFresh(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())

    @SuppressLint("MissingPermission")
    private suspend fun getSystemLocation(context: Context): Location? = withContext(Dispatchers.Main.immediate) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        // 注册、回调与移除均在主线程，避免取消与注册交错导致监听器泄漏。
        val listeners = mutableListOf<LocationListener>()
        try {
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { provider ->
                    try {
                        locationManager.isProviderEnabled(provider)
                    } catch (e: Exception) {
                        Log.w(TAG, "无法检查定位源 $provider: ${e.message}")
                        false
                    }
                }
            val lastLocation = providers
                .mapNotNull { provider ->
                    try {
                        locationManager.getLastKnownLocation(provider)?.takeIf(::isFresh)
                    } catch (e: Exception) {
                        Log.w(TAG, "无法读取 $provider 缓存: ${e.message}")
                        null
                    }
                }
                .maxByOrNull { it.elapsedRealtimeNanos }

            lastLocation ?: withTimeoutOrNull(SYSTEM_LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    var registeredCount = 0
                    for (provider in providers) {
                        if (!continuation.isActive) break
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (continuation.isActive && isFresh(location)) {
                                    Log.d(TAG, "系统定位成功: provider=$provider, accuracy=${location.accuracy}")
                                    continuation.resume(location)
                                }
                            }

                            // Android 8–10 的接口没有这些方法的默认实现。
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }
                        listeners.add(listener)
                        try {
                            // 同时请求两个来源，GPS 开启但室内无信号时仍可通过网络定位。
                            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                            registeredCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "无法请求 $provider 定位: ${e.message}")
                        }
                    }
                    if (registeredCount == 0 && continuation.isActive) {
                        Log.w(TAG, "没有可请求的系统定位源")
                        continuation.resume(null)
                    }
                }
            }.also {
                if (it == null) Log.w(TAG, "系统定位未返回新鲜位置，启用来源=$providers")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "系统定位失败：缺少定位权限")
            null
        } catch (e: Exception) {
            Log.w(TAG, "系统定位失败: ${e.message}")
            null
        } finally {
            listeners.forEach { listener ->
                try {
                    locationManager.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "移除定位监听失败: ${e.message}")
                }
            }
        }
    }
}
