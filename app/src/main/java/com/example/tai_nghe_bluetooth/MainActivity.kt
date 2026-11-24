package com.example.tai_nghe_bluetooth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tai_nghe_bluetooth.ui.theme.Tai_nghe_bluetoothTheme

class MainActivity : ComponentActivity() {

    private var usageAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Tai_nghe_bluetoothTheme {
                AirPodsPopupScreen(usageAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageAccess = hasUsageAccess(this)
    }
}

@Composable
fun AirPodsPopupScreen(usageAccess: Boolean = false) {
    val context = LocalContext.current

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                context.startService(
                    Intent(context, OverlayService::class.java)
                )
            }
        }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permission = Manifest.permission.BLUETOOTH_CONNECT

            if (context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                context.startService(
                    Intent(context, OverlayService::class.java)
                )
            } else {
                bluetoothPermissionLauncher.launch(permission)
            }
        } else {
            context.startService(
                Intent(context, OverlayService::class.java)
            )
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                context.getSharedPreferences(
                    "popup_settings",
                    android.content.Context.MODE_PRIVATE
                ).edit()
                    .putString("video_uri", uri.toString())
                    .apply()

                // Khởi động lại dịch vụ để nhận video mới
                context.stopService(
                    Intent(context, OverlayService::class.java)
                )

                context.startService(
                    Intent(context, OverlayService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "AIRPODS POPUP",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Tự động hiển thị video khi tai nghe kết nối Bluetooth")

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = {
                videoPicker.launch(arrayOf("video/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Chọn video")
        }

        Spacer(modifier = Modifier.height(16.dp))

        val prefs = remember {
            context.getSharedPreferences("popup_settings", android.content.Context.MODE_PRIVATE)
        }
        var muted by remember { mutableStateOf(prefs.getBoolean("muted", false)) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tắt tiếng video popup")
            Switch(
                checked = muted,
                onCheckedChange = {
                    muted = it
                    prefs.edit().putBoolean("muted", it).apply()
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---------- Hiện pin tai nghe (quét BLE cho AirPods) ----------
        val blePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        var hasBlePermission by remember {
            mutableStateOf(
                context.checkSelfPermission(blePermission) == PackageManager.PERMISSION_GRANTED
            )
        }
        val blePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasBlePermission = granted }

        var showBattery by remember { mutableStateOf(prefs.getBoolean("show_battery", true)) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Hiện pin tai nghe trên popup")
            Switch(
                checked = showBattery,
                onCheckedChange = {
                    showBattery = it
                    prefs.edit().putBoolean("show_battery", it).apply()
                    if (it && !hasBlePermission) blePermissionLauncher.launch(blePermission)
                }
            )
        }

        if (showBattery && !hasBlePermission) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { blePermissionLauncher.launch(blePermission) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cấp quyền quét để đọc pin Trái / Phải / Hộp")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------- Tuỳ chỉnh hiển thị video ----------
        Text(
            text = "Hiển thị video",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        var rotation by remember { mutableStateOf(prefs.getInt("video_rotation", 0)) }
        var fit by remember { mutableStateOf(prefs.getBoolean("video_fit", false)) }
        var anchor by remember { mutableStateOf(prefs.getInt("video_anchor", 1)) }
        var zoom by remember { mutableStateOf(prefs.getFloat("video_zoom", 1f)) }

        Spacer(modifier = Modifier.height(8.dp))

        // 1) Xoay video
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Xoay: ${rotation}°")
            OutlinedButton(onClick = {
                rotation = (rotation + 90) % 360
                prefs.edit().putInt("video_rotation", rotation).apply()
            }) {
                Text("Xoay thêm 90°")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2) Chế độ hiển thị
        Text("Chế độ hiển thị", modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pickFit = { value: Boolean ->
                fit = value
                prefs.edit().putBoolean("video_fit", value).apply()
            }
            if (!fit) Button(onClick = { pickFit(false) }, modifier = Modifier.weight(1f)) { Text("Lấp đầy") }
            else OutlinedButton(onClick = { pickFit(false) }, modifier = Modifier.weight(1f)) { Text("Lấp đầy") }
            if (fit) Button(onClick = { pickFit(true) }, modifier = Modifier.weight(1f)) { Text("Vừa khung") }
            else OutlinedButton(onClick = { pickFit(true) }, modifier = Modifier.weight(1f)) { Text("Vừa khung") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3) Giữ phần nào khi bị cắt
        Text("Giữ phần khi bị cắt", modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = listOf("Trên/Trái", "Giữa", "Dưới/Phải")
            labels.forEachIndexed { index, label ->
                val pick = {
                    anchor = index
                    prefs.edit().putInt("video_anchor", index).apply()
                }
                if (anchor == index) Button(onClick = pick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                else OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4) Phóng to
        Text(
            text = "Phóng to: ${"%.1f".format(zoom)}x",
            modifier = Modifier.fillMaxWidth()
        )
        Slider(
            value = zoom,
            onValueChange = { zoom = it },
            onValueChangeFinished = { prefs.edit().putFloat("video_zoom", zoom).apply() },
            valueRange = 1f..3f,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                rotation = 0; fit = false; anchor = 1; zoom = 1f
                prefs.edit()
                    .putInt("video_rotation", 0)
                    .putBoolean("video_fit", false)
                    .putInt("video_anchor", 1)
                    .putFloat("video_zoom", 1f)
                    .apply()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Đặt lại mặc định")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                // Hiện thử popup ngay để xem các chỉnh sửa (cần đã chọn video).
                context.startService(
                    Intent(context, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_PREVIEW)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Xem thử popup")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                )
            },
            enabled = !usageAccess,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (usageAccess) "Đã cấp quyền: popup tự ẩn khi mở app khác"
                else "Cấp quyền để popup tự ẩn khi mở app khác"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Dịch vụ theo dõi Bluetooth sẽ được khởi động khi mở ứng dụng."
        )
    }
}