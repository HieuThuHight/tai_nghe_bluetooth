package com.example.tai_nghe_bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Fades the popup content smoothly into the transparent overlay at its outer edges. */
private class EdgeFadeLayout(context: Context) : FrameLayout(context) {
    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var shaderWidth = 0f
    private var shaderHeight = 0f

    private fun updateMaskShader(w: Float, h: Float) {
        if (w == shaderWidth && h == shaderHeight) return

        val opaque = Color.WHITE
        val edge = Color.argb(165, 255, 255, 255)
        val stops = floatArrayOf(0f, 0.1f, 0.9f, 1f)
        val horizontal = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(edge, opaque, opaque, edge),
            stops,
            Shader.TileMode.CLAMP
        )
        val vertical = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(edge, opaque, opaque, edge),
            stops,
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = ComposeShader(horizontal, vertical, PorterDuff.Mode.MULTIPLY)
        shaderWidth = w
        shaderHeight = h
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val saved = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        updateMaskShader(w, h)
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(saved)
    }
}

/** Kiểm tra app đã được cấp quyền "Truy cập dữ liệu sử dụng" chưa (dùng để biết app nào đang mở). */
fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

/**
 * Hiệu ứng động: hai tai nghe nhấp nhô nhẹ, sóng âm lan toả ra hai bên.
 * Số pin (Trái / Phải / Hộp sạc) được vẽ ngay trên hiệu ứng này.
 * Tự chạy khi gắn vào cửa sổ và tự dừng khi popup bị gỡ.
 */
private class EarbudsAnimView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * sp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * d, 0f, 1f * d, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * sp
        color = Color.argb(210, 255, 255, 255)
        setShadowLayer(3f * d, 0f, 1f * d, Color.BLACK)
    }
    private val rect = RectF()
    private var phase = 0f

    // Dữ liệu pin (-1 = chưa biết).
    private var showBattery = false
    private var leftPct = -1
    private var rightPct = -1
    private var casePct = -1
    private var overallPct = -1
    private var chargeL = false
    private var chargeR = false
    private var chargeCase = false
    private var statusText: String? = null

    fun setBattery(
        enabled: Boolean,
        left: Int, right: Int, case: Int, overall: Int,
        chargingLeft: Boolean, chargingRight: Boolean, chargingCase: Boolean,
        status: String?
    ) {
        showBattery = enabled
        leftPct = left
        rightPct = right
        casePct = case
        overallPct = overall
        chargeL = chargingLeft
        chargeR = chargingRight
        chargeCase = chargingCase
        statusText = status
        invalidate()
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun levelColor(level: Int): Int = when {
        level < 0 -> Color.WHITE
        level >= 40 -> Color.rgb(120, 255, 170)
        level >= 20 -> Color.rgb(255, 200, 80)
        else -> Color.rgb(255, 105, 95)
    }

    private fun drawPct(canvas: Canvas, level: Int, charging: Boolean, x: Float, y: Float, prefix: String = "") {
        pctPaint.color = levelColor(level)
        val text = prefix + (if (level >= 0) "$level%" else "--") + (if (charging) " ⚡" else "")
        canvas.drawText(text, x, y, pctPaint)
    }

    private fun drawBattery(canvas: Canvas, cx: Float, cy: Float, gap: Float) {
        val baseY = height - 10f * d
        if (leftPct >= 0 || rightPct >= 0) {
            drawPct(canvas, leftPct, chargeL, cx - gap, baseY)
            drawPct(canvas, rightPct, chargeR, cx + gap, baseY)
            if (casePct >= 0) {
                canvas.drawText("Hộp sạc", cx, cy - 3f * d, labelPaint)
                drawPct(canvas, casePct, chargeCase, cx, cy + 14f * d)
            }
        } else if (overallPct >= 0) {
            drawPct(canvas, overallPct, false, cx, baseY, "Pin ")
        } else {
            statusText?.let {
                pctPaint.color = Color.WHITE
                canvas.drawText(it, cx, baseY, pctPaint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - 4f * d
        val headR = 9f * d
        val gap = 34f * d

        for (side in intArrayOf(-1, 1)) {
            val bx = cx + side * gap

            // Sóng âm lan ra phía ngoài của mỗi tai nghe.
            for (i in 0..2) {
                val t = (phase + i / 3f) % 1f
                val radius = headR + 6f * d + t * 18f * d
                ringPaint.color = Color.argb(((1f - t) * 210).toInt(), 120, 215, 255)
                rect.set(bx - radius, cy - radius, bx + radius, cy + radius)
                val center = if (side < 0) 180f else 0f
                canvas.drawArc(rect, center - 38f, 76f, false, ringPaint)
            }

            // Quầng sáng thở nhẹ phía sau tai nghe.
            val pulse = 0.5f + 0.5f * Math.sin(phase * 2.0 * Math.PI).toFloat()
            glowPaint.color = Color.argb((35 + 45 * pulse).toInt(), 120, 215, 255)
            canvas.drawCircle(bx, cy + 6f * d, headR + 8f * d + 3f * d * pulse, glowPaint)

            // Tai nghe: đầu tròn + cuống, nhấp nhô lệch pha giữa hai bên.
            val bob = Math.sin(phase * 2.0 * Math.PI + if (side < 0) 0.0 else Math.PI / 2).toFloat() * 3f * d
            canvas.save()
            canvas.translate(bx, cy + bob)
            canvas.rotate(-side * 12f)
            rect.set(-3f * d, 0f, 3f * d, 22f * d)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, bodyPaint)
            canvas.drawCircle(0f, 0f, headR, bodyPaint)
            canvas.drawCircle(side * 2f * d, -1f * d, 3.5f * d, meshPaint)
            canvas.restore()
        }

        if (showBattery) drawBattery(canvas, cx, cy, gap)
    }
}

class OverlayService : Service() {

    companion object {
        // Action/extra ẩn của hệ thống khi mức pin thiết bị Bluetooth thay đổi.
        private const val ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL"

        // Action để app yêu cầu hiện thử popup.
        const val ACTION_PREVIEW = "com.example.tai_nghe_bluetooth.ACTION_PREVIEW"

        // Mã công ty Apple (0x004C) - nhiều tai nghe báo pin qua lệnh +IPHONEACCEV.
        private const val COMPANY_ID_APPLE = 76

        private const val BATTERY_POLL_INTERVAL_MS = 2000L
        private const val BATTERY_GIVE_UP_AFTER_POLLS = 5

        // Khoá metadata (ẩn) của BluetoothDevice cho tai nghe true wireless.
        private const val METADATA_LEFT_BATTERY = 10
        private const val METADATA_RIGHT_BATTERY = 11
        private const val METADATA_CASE_BATTERY = 12

        // Thời gian popup hiện dần từ trong suốt sang rõ.
        private const val FADE_IN_DURATION_MS = 450L

        // ---- Quét BLE để đọc pin AirPods (Trái / Phải / Hộp sạc) ----
        // Chỉ nhận gói từ thiết bị ở gần (tránh lấy nhầm AirPods của người khác).
        private const val BLE_MIN_RSSI = -72
        // Tự dừng quét sau thời gian này để tiết kiệm pin điện thoại.
        private const val BLE_SCAN_TIMEOUT_MS = 26_000L
        private const val BLE_STALE_MS = 4_000L

        // ---- Kích thước popup (chỉnh ở đây cho tiện) ----
        // Chiều rộng card so với chiều rộng màn hình (trước đây 0.78).
        private const val CARD_WIDTH_FRACTION = 1f
        // Chiều cao video tăng thêm (tỉ lệ theo chiều cao màn hình) = ~1/2 khoảng trắng.
        private const val EXTRA_VIDEO_HEIGHT_FRACTION = 0.08f
        // Chiều cao video tối đa (tỉ lệ theo chiều cao màn hình).
        private const val MAX_VIDEO_HEIGHT_FRACTION = 0.45f

        // ---- Tự ẩn popup khi mở app khác ----
        private const val FG_POLL_INTERVAL_MS = 700L
        // Trong khoảng này sau khi popup hiện, app "nền" vẫn được cập nhật
        // (tránh nhầm popup hệ thống của tai nghe là một app khác).
        private const val FG_GRACE_MS = 2500L
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var popupView: FrameLayout? = null

    // Theo dõi app đang mở để ẩn popup khi người dùng chuyển sang app khác.
    private var fgBasePackage: String? = null
    private var fgLastCheck = 0L
    private var popupShownAt = 0L
    private var receiverRegistered = false

    // Trình phát video toàn màn hình.
    private var mediaPlayer: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var lastVideoW = 0
    private var lastVideoH = 0

    // Tắt tiếng video: được bật/tắt trong app (MainActivity), đọc lại mỗi lần popup hiện.
    private var isMuted = false

    // Thiết bị tai nghe hiện tại và mức pin đọc được gần nhất (-1 = chưa biết).
    private var currentDevice: BluetoothDevice? = null
    private var lastKnownBattery = -1

    // Pin từng phần của tai nghe true wireless (-1 = chưa biết).
    private var lastLeft = -1
    private var lastRight = -1
    private var lastCase = -1

    // Trạng thái sạc (đọc được qua BLE).
    private var chargeL = false
    private var chargeR = false
    private var chargeCase = false

    // View hiệu ứng tai nghe (đồng thời là nơi vẽ số pin).
    private var earbudsView: EarbudsAnimView? = null
    private var showBattery = true
    private var batteryStatus: String? = null
    private var batteryPollCount = 0

    // Quét BLE.
    private var bleScanner: BluetoothLeScanner? = null
    private var bleBestRssi = -127
    private var bleBestAt = 0L
    private val bleTimeout = Runnable { stopBleScan() }

    // Cài đặt hiển thị video (đặt trong app, đọc lại mỗi lần popup hiện).
    private var videoRotation = 0      // 0 / 90 / 180 / 270
    private var videoFit = false       // false = lấp đầy (cắt), true = vừa khung
    private var videoAnchor = 0.5f     // 0 = trên/trái, 0.5 = giữa, 1 = dưới/phải
    private var videoZoom = 1f         // 1.0 .. 3.0

    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = getBluetoothDevice(intent)
                    if (device != null && isAudioDevice(device)) {
                        setCurrentDevice(device)
                        showVideoPopup()
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = getBluetoothDevice(intent)
                    if (device != null && isAudioDevice(device)) {
                        hideVideoPopup()
                        if (device.address == currentDevice?.address) {
                            currentDevice = null
                            resetBatteryState()
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    if (state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF
                    ) {
                        hideVideoPopup()
                        currentDevice = null
                        resetBatteryState()
                    }
                }

                // Hệ thống báo pin thay đổi.
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    val device = getBluetoothDevice(intent)
                    val current = currentDevice
                    val sameDevice = device == null || current == null ||
                            device.address == current.address
                    if (level in 0..100 && sameDevice) {
                        lastKnownBattery = level
                        updateBatteryUi(level)
                    }
                }

                // Tai nghe tự báo pin qua kênh HFP (lệnh +IPHONEACCEV).
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    val cmd = intent.getStringExtra(
                        BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD
                    )
                    if (cmd == "+IPHONEACCEV") {
                        @Suppress("DEPRECATION")
                        val args = intent.getSerializableExtra(
                            BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS
                        ) as? Array<*>
                        parseIphoneAccevBattery(args)?.let { level ->
                            lastKnownBattery = level
                            updateBatteryUi(level)
                        }
                    }
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileProxies[profile] = proxy
            try {
                // Chỉ ghi nhớ tai nghe đang kết nối sẵn (để đọc pin),
                // KHÔNG hiện popup khi mở app / khởi động dịch vụ.
                val device = proxy.connectedDevices.firstOrNull()
                if (device != null) {
                    setCurrentDevice(device)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            profileProxies.remove(profile)
            hideVideoPopup()
        }
    }

    // Đọc pin định kỳ khi popup đang hiện (pin thường đến trễ vài giây sau khi kết nối).
    private val batteryPoller = object : Runnable {
        override fun run() {
            if (popupView == null) return
            val device = currentDevice

            if (device != null) {
                // Thử đọc pin riêng từng bên (trái/phải/hộp sạc).
                // (Chỉ ghi khi đọc được, để không xoá số do BLE đã đọc.)
                readUntetheredBattery(device, METADATA_LEFT_BATTERY).let { if (it >= 0) lastLeft = it }
                readUntetheredBattery(device, METADATA_RIGHT_BATTERY).let { if (it >= 0) lastRight = it }
                readUntetheredBattery(device, METADATA_CASE_BATTERY).let { if (it >= 0) lastCase = it }

                // Pin chung (dự phòng khi không có số riêng từng bên).
                val level = readBatteryLevel(device)
                if (level in 0..100) {
                    lastKnownBattery = level
                }
                refreshBatteryUi()
            }

            batteryPollCount++
            val hasAnyBattery = lastKnownBattery >= 0 || lastLeft >= 0 || lastRight >= 0
            if (!hasAnyBattery && batteryPollCount >= BATTERY_GIVE_UP_AFTER_POLLS) {
                if (batteryStatus == null || batteryStatus == "Đang đọc pin…") {
                    batteryStatus = "Không đọc được pin"
                }
                refreshBatteryUi()
            }

            handler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
        }
    }

    private fun latestForeground(from: Long, to: Long): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
            }
            pkg
        } catch (ex: Exception) {
            null
        }
    }

    // Kiểm tra định kỳ: nếu app đang mở khác với app lúc popup hiện -> ẩn popup.
    private val foregroundWatcher = object : Runnable {
        override fun run() {
            if (popupView == null) return
            val now = System.currentTimeMillis()
            val pkg = latestForeground(fgLastCheck - 500L, now)
            fgLastCheck = now

            if (pkg != null) {
                val base = fgBasePackage
                when {
                    now - popupShownAt < FG_GRACE_MS -> fgBasePackage = pkg
                    base == null || pkg != base -> {
                        hideVideoPopup()
                        return
                    }
                }
            }
            handler.postDelayed(this, FG_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startMonitoringNotification()
        registerBluetoothReceiver()
        checkAlreadyConnectedHeadphones()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Từ nút "Xem thử popup" trong app: dựng lại popup để xem cài đặt mới.
        if (intent?.action == ACTION_PREVIEW) {
            hideVideoPopup()
            showVideoPopup()
        }
        return START_STICKY
    }

    private fun startMonitoringNotification() {
        val channelId = "bluetooth_monitor"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Theo dõi tai nghe Bluetooth",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("AirPods Popup đang hoạt động")
            .setContentText("Đang theo dõi kết nối tai nghe Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addCategory(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY +
                        "." + COMPANY_ID_APPLE
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                bluetoothReceiver,
                filter,
                Manifest.permission.BLUETOOTH_CONNECT,
                null,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothReceiver, filter)
        }

        receiverRegistered = true
    }

    private fun checkAlreadyConnectedHeadphones() {
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: return

            if (!adapter.isEnabled) {
                hideVideoPopup()
                return
            }

            adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun getBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        return try {
            device.bluetoothClass?.majorDeviceClass ==
                    BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (e: SecurityException) {
            false
        }
    }

    // Đặt thiết bị hiện tại; nếu là thiết bị mới thì xoá pin cũ.
    private fun setCurrentDevice(device: BluetoothDevice) {
        if (currentDevice?.address != device.address) {
            resetBatteryState()
        }
        currentDevice = device
    }

    private fun deviceDisplayName(device: BluetoothDevice?): String {
        return try {
            val alias = if (Build.VERSION.SDK_INT >= 30) device?.alias else null
            alias ?: device?.name ?: "Tai nghe Bluetooth"
        } catch (e: SecurityException) {
            "Tai nghe Bluetooth"
        }
    }

    // BluetoothDevice.getBatteryLevel() là API ẩn nên gọi qua reflection.
    // Trả về -1 nếu máy/tai nghe không cung cấp.
    private fun readBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            (method.invoke(device) as? Int) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    // +IPHONEACCEV=<số cặp>,<khoá>,<giá trị>,... ; khoá 1 = pin, giá trị 0..9 -> 10%..100%.
    private fun parseIphoneAccevBattery(args: Array<*>?): Int? {
        if (args == null || args.isEmpty()) return null
        val count = (args[0] as? Int) ?: return null
        var i = 1
        repeat(count) {
            val key = args.getOrNull(i) as? Int
            val value = args.getOrNull(i + 1) as? Int
            if (key == 1 && value != null) {
                return ((value + 1) * 10).coerceIn(0, 100)
            }
            i += 2
        }
        return null
    }

    private fun resetBatteryState() {
        lastKnownBattery = -1
        lastLeft = -1
        lastRight = -1
        lastCase = -1
        chargeL = false
        chargeR = false
        chargeCase = false
    }

    // Đọc pin từng phần từ metadata ẩn của thiết bị. Trả về -1 nếu không có.
    private fun readUntetheredBattery(device: BluetoothDevice, key: Int): Int {
        return try {
            val method = BluetoothDevice::class.java.getMethod(
                "getMetadata",
                Int::class.javaPrimitiveType
            )
            val bytes = method.invoke(device, key) as? ByteArray ?: return -1
            val value = String(bytes, Charsets.UTF_8).trim().toIntOrNull() ?: return -1
            if (value in 0..100) value else -1
        } catch (e: Exception) {
            -1
        }
    }

    // Cập nhật pin chung (nguồn: thông báo hệ thống / lệnh HFP).
    private fun updateBatteryUi(level: Int) {
        lastKnownBattery = level
        refreshBatteryUi()
    }

    // Đẩy số pin hiện có lên popup (vẽ trên hiệu ứng tai nghe).
    private fun refreshBatteryUi() {
        earbudsView?.setBattery(
            showBattery,
            lastLeft, lastRight, lastCase, lastKnownBattery,
            chargeL, chargeR, chargeCase,
            batteryStatus
        )
    }

    // ---------------- Đọc pin AirPods qua BLE ----------------

    private fun hasBleScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleBleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleBleResult(it) }
        }
    }

    private fun startBleScan() {
        stopBleScan()
        if (!showBattery) return
        if (!hasBleScanPermission()) {
            batteryStatus = "Chưa cấp quyền đọc pin"
            refreshBatteryUi()
            return
        }
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val scanner = manager.adapter?.bluetoothLeScanner ?: return
            // Chỉ nhận gói quảng bá của Apple có kiểu 0x07 (AirPods "proximity pairing").
            val filter = ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID_APPLE, byteArrayOf(0x07), byteArrayOf(0xFF.toByte()))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            bleBestRssi = -127
            bleBestAt = 0L
            scanner.startScan(listOf(filter), settings, bleCallback)
            bleScanner = scanner
            handler.postDelayed(bleTimeout, BLE_SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopBleScan() {
        handler.removeCallbacks(bleTimeout)
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        bleScanner = null
    }

    // Giải mã gói AirPods (định dạng do cộng đồng dò ngược, không phải tài liệu chính thức của Apple):
    // [0]=0x07 [1]=độ dài [2]=0x01 [3..4]=model [5]=trạng thái [6]=pin 2 tai [7]=cờ sạc | pin hộp
    private fun handleBleResult(result: ScanResult) {
        if (popupView == null) return
        val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID_APPLE) ?: return
        if (data.size < 8 || data[0] != 0x07.toByte()) return

        val now = System.currentTimeMillis()
        val rssi = result.rssi
        if (rssi < BLE_MIN_RSSI) return
        // Đang có thiết bị mạnh hơn nhiều thì bỏ qua thiết bị yếu (AirPods của người khác).
        if (now - bleBestAt < BLE_STALE_MS && rssi < bleBestRssi - 10) return
        bleBestRssi = rssi
        bleBestAt = now

        val status = data[5].toInt() and 0xFF
        val pods = data[6].toInt() and 0xFF
        val flags = data[7].toInt() and 0xFF

        // Bit này cho biết hai tai đang bị đảo trái/phải trong gói tin.
        val flip = ((status shr 4) and 0x02) == 0
        val hi = (pods shr 4) and 0x0F
        val lo = pods and 0x0F
        val leftN = if (flip) hi else lo
        val rightN = if (flip) lo else hi
        val caseN = flags and 0x0F
        val charge = (flags shr 4) and 0x0F

        fun toPct(n: Int): Int = when {
            n in 0..9 -> n * 10 + 5   // mỗi mức ~10%
            n == 10 -> 100
            else -> -1                 // 15 = chưa biết
        }

        val l = toPct(leftN)
        val r = toPct(rightN)
        val c = toPct(caseN)
        if (l < 0 && r < 0 && c < 0) return

        lastLeft = l
        lastRight = r
        lastCase = c
        chargeL = (charge and (if (flip) 2 else 1)) != 0
        chargeR = (charge and (if (flip) 1 else 2)) != 0
        chargeCase = (charge and 4) != 0
        batteryStatus = null
        refreshBatteryUi()
    }

    // ---------------- Video toàn màn hình ----------------

    private fun startPlayer(surface: Surface, textureView: TextureView, uri: Uri) {
        releasePlayer()
        playerSurface = surface
        try {
            val player = MediaPlayer()
            player.setDataSource(this, uri)
            player.setSurface(surface)
            player.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
            player.isLooping = false
            player.setOnVideoSizeChangedListener { _, w, h ->
                lastVideoW = w
                lastVideoH = h
                applyFit(textureView)
            }
            player.setOnPreparedListener { it.start() }
            // Hết video thì giữ nguyên khung hình cuối (không phát lại).
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Hiển thị video theo cài đặt: xoay, vừa khung / lấp đầy, phóng to, vị trí cắt.
    private fun applyFit(textureView: TextureView) {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f || lastVideoW == 0 || lastVideoH == 0) return

        val cx = viewW / 2f
        val cy = viewH / 2f

        // Kích thước video sau khi xoay thủ công.
        val swapped = videoRotation == 90 || videoRotation == 270
        val ew = if (swapped) lastVideoH.toFloat() else lastVideoW.toFloat()
        val eh = if (swapped) lastVideoW.toFloat() else lastVideoH.toFloat()

        val base = if (videoFit) minOf(viewW / ew, viewH / eh) else maxOf(viewW / ew, viewH / eh)
        val scale = base * videoZoom

        val matrix = Matrix()
        // 1) TextureView mặc định kéo giãn video cho vừa khung -> trả về đúng tỉ lệ gốc.
        matrix.setScale(lastVideoW / viewW, lastVideoH / viewH, cx, cy)
        // 2) Xoay.
        matrix.postRotate(videoRotation.toFloat(), cx, cy)
        // 3) Phóng (vừa khung / lấp đầy) nhân với mức zoom.
        matrix.postScale(scale, scale, cx, cy)
        // 4) Dịch để giữ phần trên/giữa/dưới khi video lớn hơn khung.
        val overflowX = maxOf(0f, ew * scale - viewW)
        val overflowY = maxOf(0f, eh * scale - viewH)
        matrix.postTranslate((0.5f - videoAnchor) * overflowX, (0.5f - videoAnchor) * overflowY)
        textureView.setTransform(matrix)
    }

    // Đọc tỉ lệ rộng/cao của video trước khi dựng popup (mặc định 16:9 nếu không đọc được).
    private fun readVideoAspect(uri: Uri): Float {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val w = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                when {
                    w <= 0 || h <= 0 -> 16f / 9f
                    rotation == 90 || rotation == 270 -> h.toFloat() / w
                    else -> w.toFloat() / h
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            16f / 9f
        }
    }

    private fun applyVolume() {
        val v = if (isMuted) 0f else 1f
        try {
            mediaPlayer?.setVolume(v, v)
        } catch (_: Exception) {
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null

        try {
            playerSurface?.release()
        } catch (_: Exception) {
        }
        playerSurface = null

        lastVideoW = 0
        lastVideoH = 0
    }

    // ---------------- Popup ----------------

    private fun showVideoPopup() {
        // Không tạo popup thứ hai nếu popup hiện tại đang được gắn vào WindowManager.
        if (popupView != null) return

        val prefs = getSharedPreferences("popup_settings", MODE_PRIVATE)
        val videoUri = prefs.getString("video_uri", null) ?: return

        isMuted = prefs.getBoolean("muted", false)
        videoRotation = (prefs.getInt("video_rotation", 0) % 360 + 360) % 360
        videoFit = prefs.getBoolean("video_fit", false)
        videoAnchor = when (prefs.getInt("video_anchor", 1)) {
            0 -> 0f
            2 -> 1f
            else -> 0.5f
        }
        videoZoom = prefs.getFloat("video_zoom", 1f).coerceIn(1f, 3f)
        showBattery = prefs.getBoolean("show_battery", false)
        batteryStatus = if (showBattery) "Đang đọc pin…" else null

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // Lớp gốc phủ toàn màn hình. Bấm vào vùng ngoài khung thông tin sẽ ẩn popup.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideVideoPopup() }
            alpha = 0f // sẽ hiện dần lên sau khi gắn vào cửa sổ
        }

        // Kích thước popup theo tỉ lệ video để hiển thị ĐỦ video, không bị cắt.
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var aspect = readVideoAspect(Uri.parse(videoUri))
        // Xoay 90°/270° thì tỉ lệ khung đảo lại.
        if (videoRotation == 90 || videoRotation == 270) aspect = 1f / aspect

        var cardW = (screenW * CARD_WIDTH_FRACTION).toInt()
        var videoH = (cardW / aspect).toInt()
        val maxVideoH = (screenH * MAX_VIDEO_HEIGHT_FRACTION).toInt()
        if (videoH > maxVideoH) {
            // Video dọc/cao: thu nhỏ khung lại cho vừa chiều cao tối đa.
            cardW = (maxVideoH * aspect).toInt().coerceAtLeast(dp(220))
            videoH = (cardW / aspect).toInt()
        } else if (!videoFit) {
            // Video ngang (chế độ lấp đầy): kéo cao thêm ~1/2 khoảng trắng phía trên.
            val extra = (screenH * EXTRA_VIDEO_HEIGHT_FRACTION).toInt()
            videoH = (videoH + extra).coerceAtMost(maxVideoH)
        }
        // Đủ cao để chứa tên + hiệu ứng tai nghe + số pin.
        videoH = maxOf(videoH, dp(200))

        // Khung popup (card): tiêu đề trên cùng, video ở giữa, pin ở dưới.
        val cardRadius = dp(32).toFloat()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 35))
                cornerRadius = cardRadius
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
                }
            }
            clipToOutline = true
            isClickable = true // bấm trong card thì không đóng
        }
        val fadedCard = EdgeFadeLayout(this).apply {
            addView(
                card,
                FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
        }

        // Video phủ kín card; tên + nút đóng + pin được vẽ ĐÈ LÊN video.
        val videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val textureView = TextureView(this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                startPlayer(Surface(st), textureView, Uri.parse(videoUri))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyFit(textureView)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        videoFrame.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Lớp tối nhẹ ở trên/dưới để chữ đọc rõ trên mọi video.
        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0))
            )
        }
        videoFrame.addView(
            topScrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(90)).apply {
                gravity = Gravity.TOP
            }
        )
        val bottomScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(170, 0, 0, 0))
            )
        }
        videoFrame.addView(
            bottomScrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(130)).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // Tên tai nghe + nút đóng (đè lên phía trên video).
        val header = FrameLayout(this)
        val deviceName = TextView(this).apply {
            text = deviceDisplayName(currentDevice)
            textSize = 18f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(44), 0, dp(44), 0)
        }
        header.addView(
            deviceName,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 85, 85, 85))
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { hideVideoPopup() }
        }
        header.addView(
            closeButton,
            FrameLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        videoFrame.addView(
            header,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                gravity = Gravity.TOP
                topMargin = dp(10)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )

        // Hiệu ứng động hai tai nghe + sóng âm; số pin vẽ ngay trên hiệu ứng này.
        val earbudsAnim = EarbudsAnimView(this)
        earbudsAnim.setBattery(
            showBattery, -1, -1, -1, -1, false, false, false, batteryStatus
        )
        videoFrame.addView(
            earbudsAnim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(88)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(4)
            }
        )

        card.addView(videoFrame, LinearLayout.LayoutParams(cardW, videoH))

        // Card nằm sát đáy màn hình; phần còn lại là vùng bấm để đóng.
        root.addView(
            fadedCard,
            FrameLayout.LayoutParams(
                cardW,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 0
            }
        )

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // Bắt buộc để TextureView (video) vẽ được trong cửa sổ overlay.
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(root, windowParams)
            popupView = root
            earbudsView = earbudsAnim

            // Hiện dần từ trong suốt sang rõ.
            root.animate().alpha(1f).setDuration(FADE_IN_DURATION_MS).start()

            // Hiện ngay mức pin đã biết (nếu có), rồi tiếp tục đọc định kỳ.
            refreshBatteryUi()
            batteryPollCount = 0
            handler.removeCallbacks(batteryPoller)
            handler.post(batteryPoller)

            // Quét BLE để lấy pin riêng từng bên của AirPods (chỉ trong lúc popup hiện).
            startBleScan()

            // Tự ẩn popup khi người dùng mở app khác (cần quyền "Truy cập dữ liệu sử dụng").
            handler.removeCallbacks(foregroundWatcher)
            if (hasUsageAccess(this)) {
                val now = System.currentTimeMillis()
                popupShownAt = now
                fgLastCheck = now
                fgBasePackage = latestForeground(now - 60L * 60L * 1000L, now)
                handler.postDelayed(foregroundWatcher, FG_POLL_INTERVAL_MS)
            }
        } catch (e: Exception) {
            popupView = null
            earbudsView = null
            e.printStackTrace()
        }
    }

    private fun hideVideoPopup() {
        handler.removeCallbacks(batteryPoller)
        handler.removeCallbacks(foregroundWatcher)
        stopBleScan()

        releasePlayer()
        earbudsView = null

        popupView?.let { view ->
            try {
                view.animate().cancel()
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        popupView = null
    }

    override fun onDestroy() {
        hideVideoPopup()
        handler.removeCallbacksAndMessages(null)

        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        profileProxies.forEach { (profile, proxy) ->
            try {
                manager.adapter?.closeProfileProxy(profile, proxy)
            } catch (_: Exception) {
            }
        }
        profileProxies.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}