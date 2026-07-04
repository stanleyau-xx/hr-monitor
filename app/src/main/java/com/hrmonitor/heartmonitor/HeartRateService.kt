package com.hrmonitor.heartmonitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.UUID

@SuppressLint("MissingPermission")
class HeartRateService : Service() {

    companion object {
        private const val TAG = "HeartRateService"
        private val HR_SERVICE_UUID: UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CHANNEL_ID = "hr_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.hrmonitor.ACTION_STOP"
    }

    // ── BLE ──
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Overlay ──
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var tvOverlayBpm: TextView? = null

    // ═══════════════════════════════════════════════════
    // BLE Scan Callback
    // ═══════════════════════════════════════════════════
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.device.address
            Log.i(TAG, "Found device: $name")
            scanner?.stopScan(this)
            handler.post { connectToDevice(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            handler.post {
                updateStatus("Scan failed, retrying...")
                scheduleRetryScan()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // BLE GATT Callback
    // ═══════════════════════════════════════════════════
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    handler.post { updateStatus("Connected, discovering services...") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    gatt.close()
                    bluetoothGatt = null
                    handler.post {
                        updateBpm(0)
                        updateStatus("Disconnected, reconnecting...")
                        scheduleRetryScan()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { updateStatus("Service discovery failed") }
                return
            }

            val hrService = gatt.getService(HR_SERVICE_UUID) ?: run {
                handler.post { updateStatus("HR Service not found on device") }
                return
            }

            val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_UUID) ?: run {
                handler.post { updateStatus("HR Characteristic not found") }
                return
            }

            // Step 1: Enable notifications locally
            gatt.setCharacteristicNotification(hrChar, true)

            // Step 2: Write ENABLE_NOTIFICATION_VALUE to CCCD descriptor
            val descriptor = hrChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }

            handler.post { updateStatus("Monitoring ❤️") }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled successfully")
                handler.post { updateStatus("Monitoring ❤️") }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleHeartRateData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleHeartRateData(value)
        }
    }

    // ═══════════════════════════════════════════════════
    // Service Lifecycle
    // ═══════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        scanner = bluetoothManager.adapter?.bluetoothLeScanner
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("--")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        HeartRateState.isRunning = true
        startBleScan()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════
    // BLE Operations
    // ═══════════════════════════════════════════════════
    private fun startBleScan() {
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth is not enabled")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateStatus("BLE scanner unavailable")
            return
        }

        updateStatus("Searching for heart rate strap...")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
            updateStatus("Bluetooth permission denied")
            return
        }

        // Auto-stop scan after 15 seconds and retry
        handler.postDelayed({
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
            if (bluetoothGatt == null) {
                startBleScan()
            }
        }, 15_000)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun scheduleRetryScan() {
        handler.postDelayed({ startBleScan() }, 5_000)
    }

    private fun handleHeartRateData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val bpm = HeartRateParser.parse(data)
        handler.post { updateBpm(bpm) }
    }

    // ═══════════════════════════════════════════════════
    // Overlay Window
    // ═══════════════════════════════════════════════════
    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_heart_rate, null)
        tvOverlayBpm = overlayView?.findViewById(R.id.tvOverlayBpm)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(12)
            y = dpToPx(48) // below status bar
        }

        // Make overlay draggable
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun updateOverlayBpm(bpm: Int) {
        tvOverlayBpm?.text = if (bpm > 0) "$bpm" else "--"
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    // ═══════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart Rate Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows heart rate monitoring status"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(bpm: String): Notification {
        // Stop action
        val stopIntent = Intent(this, HeartRateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap to open app
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("❤️ HR Monitor")
            .setContentText("$bpm BPM")
            .setSmallIcon(R.drawable.ic_heart)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_heart, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(bpm: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(if (bpm > 0) "$bpm" else "--"))
    }

    // ═══════════════════════════════════════════════════
    // State Updates
    // ═══════════════════════════════════════════════════
    private fun updateBpm(bpm: Int) {
        HeartRateState.bpm = bpm
        HeartRateState.onBpmChanged?.invoke(bpm)
        updateOverlayBpm(bpm)
        updateNotification(bpm)
    }

    private fun updateStatus(status: String) {
        HeartRateState.status = status
        HeartRateState.onStatusChanged?.invoke(status)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun cleanup() {
        HeartRateState.isRunning = false
        HeartRateState.bpm = 0
        HeartRateState.status = "Stopped"

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        removeOverlay()
        handler.removeCallbacksAndMessages(null)
    }
}
