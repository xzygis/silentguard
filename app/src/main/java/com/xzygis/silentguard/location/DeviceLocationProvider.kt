package com.xzygis.silentguard.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 统一的设备定位入口，供前台地图页与后台守护服务共用。
 *
 * 优先使用 Google Play Services 的 FusedLocationProvider；当 GMS 不可用
 * （如华为等无 GMS 机型）或返回空结果时，自动降级到 Android 原生
 * LocationManager（GPS / 网络定位）。这样后台服务在无 GMS 设备上也能
 * 正常获取定位，避免误报"异常未定位告警"。
 */
object DeviceLocationProvider {

    private const val TAG = "DeviceLocation"
    private const val SYSTEM_LOCATION_TIMEOUT_MS = 10_000L

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

        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.getCurrentLocation(priority, CancellationTokenSource().token).await()
                ?: fusedClient.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "GMS 定位失败，改用系统定位: ${e.message}")
            null
        }
    }

    private suspend fun getSystemLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        return try {
            val providers = locationManager.getProviders(true)
            val lastLocation = providers
                .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }

            lastLocation ?: withTimeoutOrNull(SYSTEM_LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val provider = when {
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> providers.firstOrNull()
                    }

                    if (provider == null) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    var resumed = false
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!resumed) {
                                resumed = true
                                continuation.resume(location)
                                locationManager.removeUpdates(this)
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        locationManager.removeUpdates(listener)
                    }
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "系统定位失败：缺少定位权限")
            null
        } catch (e: Exception) {
            Log.w(TAG, "系统定位失败: ${e.message}")
            null
        }
    }
}
