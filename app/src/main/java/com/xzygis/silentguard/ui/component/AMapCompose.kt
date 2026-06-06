package com.xzygis.silentguard.ui.component

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView

private const val TAG = "AMapView"

@Composable
fun AMapView(
    modifier: Modifier = Modifier,
    onMapReady: (AMap) -> Unit = {}
) {
    val context = LocalContext.current
    var mapError by remember { mutableStateOf(false) }

    Log.d(TAG, "[init] AMapView Composable 开始组合")

    val mapView = remember {
        Log.d(TAG, "[init] 开始创建 MapView 实例")
        try {
            val view = MapView(context)
            Log.i(TAG, "[init] MapView 实例创建成功: $view")
            view
        } catch (e: Exception) {
            Log.e(TAG, "[init] MapView 实例创建失败: ${e.message}", e)
            mapError = true
            null
        }
    }

    if (mapError || mapView == null) {
        Log.w(TAG, "[init] 地图不可用，显示占位符 (mapError=$mapError, mapView=$mapView)")
        MapUnavailablePlaceholder(modifier)
        return
    }

    // 设置全局未捕获异常处理，防止 GLThread 崩溃导致应用闪退
    val defaultHandler = remember { Thread.getDefaultUncaughtExceptionHandler() }
    DisposableEffect(Unit) {
        Log.d(TAG, "[gl] 设置 GLThread 异常拦截器")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread.name.startsWith("GLThread")) {
                Log.e(TAG, "[gl] GLThread 崩溃已拦截 - thread=${thread.name}, error=${throwable.message}", throwable)
                mapError = true
            } else {
                Log.w(TAG, "[gl] 非 GLThread 异常，交由默认处理器 - thread=${thread.name}")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        onDispose {
            Log.d(TAG, "[gl] 移除 GLThread 异常拦截器")
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            Log.d(TAG, "[factory] AndroidView factory 开始执行")
            try {
                mapView.apply {
                    Log.d(TAG, "[factory] 调用 MapView.onCreate(null)")
                    onCreate(null)
                    Log.d(TAG, "[factory] MapView.onCreate 完成，获取 AMap 实例")
                    val aMap = map
                    Log.i(TAG, "[factory] AMap 实例获取成功: $aMap")
                    Log.d(TAG, "[factory] 调用 onMapReady 回调")
                    onMapReady(aMap)
                    Log.i(TAG, "[factory] onMapReady 回调完成，地图初始化成功")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[factory] 地图初始化失败: ${e.message}", e)
                mapError = true
                mapView
            }
        }
    )

    Log.d(TAG, "[lifecycle] 注册 MapLifecycle 监听")
    MapLifecycle(mapView)
}

@Composable
private fun MapUnavailablePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Map,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "地图加载失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "设备不支持 OpenGL 渲染",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MapLifecycle(mapView: MapView) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            Log.d(TAG, "[lifecycle] 生命周期事件: $event")
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "[lifecycle] MapView.onResume()")
                    try {
                        mapView.onResume()
                    } catch (e: Exception) {
                        Log.e(TAG, "[lifecycle] MapView.onResume() 异常: ${e.message}", e)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "[lifecycle] MapView.onPause()")
                    try {
                        mapView.onPause()
                    } catch (e: Exception) {
                        Log.e(TAG, "[lifecycle] MapView.onPause() 异常: ${e.message}", e)
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    Log.d(TAG, "[lifecycle] MapView.onDestroy()")
                    try {
                        mapView.onDestroy()
                    } catch (e: Exception) {
                        Log.e(TAG, "[lifecycle] MapView.onDestroy() 异常: ${e.message}", e)
                    }
                }
                else -> {}
            }
        }
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(config: Configuration) {
                Log.d(TAG, "[lifecycle] onConfigurationChanged")
            }
            override fun onLowMemory() {
                Log.w(TAG, "[lifecycle] onLowMemory，调用 MapView.onLowMemory()")
                mapView.onLowMemory()
            }
        }

        lifecycle.addObserver(observer)
        context.registerComponentCallbacks(callbacks)
        Log.d(TAG, "[lifecycle] 生命周期观察者已注册")

        onDispose {
            Log.d(TAG, "[lifecycle] 生命周期观察者已移除，MapView 销毁")
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
        }
    }
}
