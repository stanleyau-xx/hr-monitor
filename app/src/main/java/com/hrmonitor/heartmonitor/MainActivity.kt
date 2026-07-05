package com.hrmonitor.heartmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvBpm: TextView
    private lateinit var btnToggle: Button
    private lateinit var seekBarSize: SeekBar
    private lateinit var tvSizePreview: TextView

    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "hr_monitor_prefs"
        const val KEY_OVERLAY_SIZE = "overlay_text_size"
        const val DEFAULT_SIZE_SP = 20
        // SeekBar range: 0..36 maps to 12sp..48sp
        const val SIZE_MIN_SP = 12
        const val SIZE_MAX_SP = 48
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            checkOverlayAndStart()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startMonitoring()
        } else {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        tvStatus = findViewById(R.id.tvStatus)
        tvBpm = findViewById(R.id.tvBpm)
        btnToggle = findViewById(R.id.btnToggle)
        seekBarSize = findViewById(R.id.seekBarSize)
        tvSizePreview = findViewById(R.id.tvSizePreview)

        // Load saved size and apply
        val savedSize = prefs.getInt(KEY_OVERLAY_SIZE, DEFAULT_SIZE_SP)
        HeartRateState.overlayTextSize = savedSize
        seekBarSize.progress = savedSize - SIZE_MIN_SP
        updateSizePreview(savedSize)

        seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sizeSp = progress + SIZE_MIN_SP
                updateSizePreview(sizeSp)
                if (fromUser) {
                    HeartRateState.overlayTextSize = sizeSp
                    prefs.edit().putInt(KEY_OVERLAY_SIZE, sizeSp).apply()
                    HeartRateState.onSizeChanged?.invoke(sizeSp)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            if (HeartRateState.isRunning) {
                stopMonitoring()
            } else {
                requestAllPermissions()
            }
        }

        HeartRateState.onBpmChanged = { bpm ->
            runOnUiThread {
                tvBpm.text = if (bpm > 0) "$bpm" else "--"
            }
        }
        HeartRateState.onStatusChanged = { status ->
            runOnUiThread {
                tvStatus.text = status
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (HeartRateState.isRunning) {
            tvBpm.text = if (HeartRateState.bpm > 0) "${HeartRateState.bpm}" else "--"
            tvStatus.text = HeartRateState.status
            btnToggle.text = getString(R.string.stop_monitoring)
        } else {
            tvBpm.text = "--"
            tvStatus.text = getString(R.string.stopped)
            btnToggle.text = getString(R.string.start_monitoring)
        }
    }

    private fun requestAllPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        HeartRateState.isRunning = true
        updateUI()
    }

    private fun stopMonitoring() {
        val serviceIntent = Intent(this, HeartRateService::class.java).apply {
            action = HeartRateService.ACTION_STOP
        }
        startService(serviceIntent)
        HeartRateState.isRunning = false
        updateUI()
    }

    private fun updateSizePreview(sizeSp: Int) {
        tvSizePreview.textSize = sizeSp.toFloat()
    }
}
