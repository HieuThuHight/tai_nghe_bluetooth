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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.SurfaceTexture
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

/**
 * Lớp chứa video, làm phần TRÊN mờ dần (trong suốt) rồi đậm dần xuống dưới.
 * Dùng mặt nạ alpha (DST_IN) nên phần mờ hoà vào nền của card phía sau.
 */
private class TopFadeLayout(context: Context) : FrameLayout(context) {

    // Tỉ lệ chiều cao (tính từ trên xuống) mà video chuyển từ trong suốt sang rõ hoàn toàn.
    var fadeFraction = 0.5f

    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.saveLayer(0f, 0f, w, h, null)

        super.dispatchDraw(canvas)

        val fadeHeight = (h * fadeFraction).coerceAtLeast(1f)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, fadeHeight,
            intArrayOf(
                Color.argb(0, 0, 0, 0),     // trên cùng: trong suốt
                Color.argb(90, 0, 0, 0),    // giữa: hơi mờ
                Color.argb(255, 0, 0, 0)    // dưới: rõ hoàn toàn
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
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
 * Tự chạy khi gắn vào cửa sổ và tự dừng khi popup bị gỡ.
 */
private class EarbudsAnimView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var phase = 0f

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

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - 4f * d
        val headR = 9f * d
        val gap = 28f * d

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
    }
}

/** Một hàng pin: nhãn + thanh pin + số %. */
private class BatteryRowUi(
    val container: LinearLayout,
    val fillView: View,
    val fillDrawable: GradientDrawable,
    val percentView: TextView,
    val trackWidthPx: Int
) {
    fun setLevel(level: Int) {
        percentView.text = "$level%"
        val color = when {
            level >= 40 -> Color.rgb(0, 220, 100)
            level >= 20 -> Color.rgb(255, 170, 0)
            else -> Color.rgb(255, 69, 58)
        }
        fillDrawable.setColor(color)
        val params = fillView.layoutParams
        params.width = trackWidthPx * level / 100
        fillView.layoutParams = params
    }

    fun setUnknown(text: String) {
        percentView.text = text
        val params = fillView.layoutParams
        params.width = 0
        fillView.layoutParams = params
    }
}

class OverlayService : Service() {

    companion object {
        // Action/extra ẩn của hệ thống khi mức pin thiết bị Bluetooth thay đổi.
        private const val ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL"

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

        // Hiện thanh pin trên popup hay không (false = ẩn pin, chỉ hiện hiệu ứng tai nghe).
        private const val SHOW_BATTERY = false

        // ---- Kích thước popup (chỉnh ở đây cho tiện) ----
        // Chiều rộng card so với chiều rộng màn hình (trước đây 0.78).
        private const val CARD_WIDTH_FRACTION = 0.90f
        // Chiều cao video tăng thêm (tỉ lệ theo chiều cao màn hình) = ~1/2 khoảng trắng.
        private const val EXTRA_VIDEO_HEIGHT_FRACTION = 0.07f
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

    // Các hàng pin trong popup.
    private var overallRow: BatteryRowUi? = null
    private var leftRow: BatteryRowUi? = null
    private var rightRow: BatteryRowUi? = null
    private var caseRow: BatteryRowUi? = null
    private var batteryPollCount = 0

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
                lastLeft = readUntetheredBattery(device, METADATA_LEFT_BATTERY)
                lastRight = readUntetheredBattery(device, METADATA_RIGHT_BATTERY)
                lastCase = readUntetheredBattery(device, METADATA_CASE_BATTERY)

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
                overallRow?.setUnknown("Không đọc được pin")
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

    // Nếu có số riêng từng bên thì hiện Trái/Phải(/Hộp); nếu không thì hiện 1 hàng pin chung.
    private fun refreshBatteryUi() {
        val overall = overallRow ?: return
        val left = leftRow ?: return
        val right = rightRow ?: return
        val case = caseRow ?: return

        val hasBuds = lastLeft >= 0 || lastRight >= 0

        overall.container.visibility = if (hasBuds) View.GONE else View.VISIBLE
        left.container.visibility = if (hasBuds) View.VISIBLE else View.GONE
        right.container.visibility = if (hasBuds) View.VISIBLE else View.GONE
        case.container.visibility = if (hasBuds && lastCase >= 0) View.VISIBLE else View.GONE

        if (hasBuds) {
            applyRow(left, lastLeft)
            applyRow(right, lastRight)
            applyRow(case, lastCase)
        } else if (lastKnownBattery >= 0) {
            overall.setLevel(lastKnownBattery)
        }
    }

    private fun applyRow(row: BatteryRowUi, level: Int) {
        if (level in 0..100) row.setLevel(level) else row.setUnknown("--")
    }

    private fun buildBatteryRow(
        label: String,
        density: Float,
        trackDp: Int = 140,
        labelDp: Int = 44,
        gapDp: Int = 8,
        endGapDp: Int = 12,
        textSp: Float = 15f
    ): BatteryRowUi {
        fun dp(value: Int): Int = (value * density).toInt()
        val trackWidth = dp(trackDp)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = textSp
            setTextColor(Color.LTGRAY)
        }

        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(70, 70, 70))
                cornerRadius = dp(6).toFloat()
            }
        }
        val fillDrawable = GradientDrawable().apply {
            setColor(Color.rgb(0, 220, 100))
            cornerRadius = dp(6).toFloat()
        }
        val fillView = View(this).apply { background = fillDrawable }
        track.addView(fillView, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))

        val percentView = TextView(this).apply {
            text = "--"
            textSize = textSp
            setTextColor(Color.LTGRAY)
        }

        row.addView(
            labelView,
            LinearLayout.LayoutParams(dp(labelDp), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        row.addView(
            track,
            LinearLayout.LayoutParams(trackWidth, dp(12)).apply {
                leftMargin = dp(gapDp)
                rightMargin = dp(endGapDp)
            }
        )
        row.addView(percentView)

        return BatteryRowUi(row, fillView, fillDrawable, percentView, trackWidth)
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

    // Phóng video lấp đầy khung, giữ đúng tỉ lệ (center-crop).
    private fun applyFit(textureView: TextureView) {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f || lastVideoW == 0 || lastVideoH == 0) return

        // maxOf = lấp đầy khung (cắt nhẹ phần thừa ở hai bên), không để dải đen.
        val scale = maxOf(viewW / lastVideoW, viewH / lastVideoH)
        val scaleX = lastVideoW * scale / viewW
        val scaleY = lastVideoH * scale / viewH

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
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
        val aspect = readVideoAspect(Uri.parse(videoUri))

        var cardW = (screenW * CARD_WIDTH_FRACTION).toInt()
        var videoH = (cardW / aspect).toInt()
        val maxVideoH = (screenH * MAX_VIDEO_HEIGHT_FRACTION).toInt()
        if (videoH > maxVideoH) {
            // Video dọc/cao: thu nhỏ khung lại cho vừa chiều cao tối đa.
            cardW = (maxVideoH * aspect).toInt().coerceAtLeast(dp(220))
            videoH = (cardW / aspect).toInt()
        } else {
            // Video ngang: kéo cao thêm ~1/2 khoảng trắng phía trên.
            val extra = (screenH * EXTRA_VIDEO_HEIGHT_FRACTION).toInt()
            videoH = (videoH + extra).coerceAtMost(maxVideoH)
        }

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
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(120)).apply {
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

        // Pin đè lên phía dưới video: Trái | Phải cạnh nhau (hộp sạc bên dưới nếu có).
        // Nếu không đọc được pin từng bên thì hiện 1 hàng pin chung.
        val overall = buildBatteryRow("Pin", density)
        overall.setUnknown("Đang đọc pin…")
        val left = buildBatteryRow("Trái", density, 56, 34, 6, 6, 14f)
        val right = buildBatteryRow("Phải", density, 56, 34, 6, 6, 14f)
        val caseUi = buildBatteryRow("Hộp", density)
        for (r in listOf(left, right, caseUi)) r.container.visibility = View.GONE

        val budsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        budsRow.addView(left.container, LinearLayout.LayoutParams(0, dp(34), 1f))
        budsRow.addView(right.container, LinearLayout.LayoutParams(0, dp(34), 1f))

        val batteryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        batteryBox.addView(
            overall.container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
        )
        batteryBox.addView(
            budsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        batteryBox.addView(
            caseUi.container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
        )
        batteryBox.visibility = if (SHOW_BATTERY) View.VISIBLE else View.GONE
        videoFrame.addView(
            batteryBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(12)
            }
        )

        // Hiệu ứng động hai tai nghe + sóng âm (đè lên phía dưới video).
        val earbudsAnim = EarbudsAnimView(this)
        videoFrame.addView(
            earbudsAnim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(76)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(6)
            }
        )

        card.addView(videoFrame, LinearLayout.LayoutParams(cardW, videoH))

        // Card nằm sát đáy màn hình; phần còn lại là vùng bấm để đóng.
        root.addView(
            card,
            FrameLayout.LayoutParams(
                cardW,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
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
            overallRow = overall
            leftRow = left
            rightRow = right
            caseRow = caseUi

            // Hiện dần từ trong suốt sang rõ.
            root.animate().alpha(1f).setDuration(FADE_IN_DURATION_MS).start()

            // Hiện ngay mức pin đã biết (nếu có), rồi tiếp tục đọc định kỳ.
            refreshBatteryUi()
            batteryPollCount = 0
            handler.removeCallbacks(batteryPoller)
            handler.post(batteryPoller)

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
            overallRow = null
            leftRow = null
            rightRow = null
            caseRow = null
            e.printStackTrace()
        }
    }

    private fun hideVideoPopup() {
        handler.removeCallbacks(batteryPoller)
        handler.removeCallbacks(foregroundWatcher)

        releasePlayer()
        overallRow = null
        leftRow = null
        rightRow = null
        caseRow = null

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