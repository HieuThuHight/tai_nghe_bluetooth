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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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

        Spacer(modifier = Modifier.height(16.dp))

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