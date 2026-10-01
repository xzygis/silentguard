package com.xzygis.silentguard.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.xzygis.silentguard.config.MapPrivacy
import com.xzygis.silentguard.data.DataExport
import kotlinx.coroutines.launch

@Composable
fun DataPrivacyControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(MapPrivacy.isAllowed(context)) }
    var confirmExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                val count = DataExport.write(context, uri)
                Toast.makeText(context, "已导出 $count 条记录", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出未完成，请删除不完整文件后重试", Toast.LENGTH_LONG).show()
            } finally { exporting = false }
        }
    }
    Column {
        Text("允许高德地图、坐标转换及地址解析", style = MaterialTheme.typography.titleSmall)
        Switch(checked = allowed, onCheckedChange = {
            MapPrivacy.setAllowed(context, it)
            allowed = it
        })
        Text("关闭后后续请求停止；已生成的待发送地图邮件仍包含地图链接。高德隐私政策：https://lbs.amap.com/pages/privacy/",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !exporting, onClick = { confirmExport = true }) {
            Text(if (exporting) "正在导出…" else "导出本机记录")
        }
    }
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text("确认导出敏感数据") },
        text = { Text("导出包含全部短信正文、精确位置、时间和状态，不包含邮箱授权码。请选择可信的存储位置。") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            launcher.launch("silentguard-records.ndjson")
        }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } }
    )
}
