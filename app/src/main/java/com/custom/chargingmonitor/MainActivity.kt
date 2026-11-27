package com.custom.chargingmonitor

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var ignoreSwitchChange = false

    private lateinit var tvModeBadge: TextView
    private lateinit var btnShizuku: Button
    private lateinit var switchOverlay: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvWatt: TextView
    private lateinit var tvBatVolt: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvPpsVolt: TextView
    private lateinit var tvProtocol: TextView
    private lateinit var tvCapacity: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvCycles: TextView
    private lateinit var tvTech: TextView

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            updateUI()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModeBadge = findViewById(R.id.tvModeBadge)
        btnShizuku = findViewById(R.id.btnShizuku)
        switchOverlay = findViewById(R.id.switchOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        tvWatt = findViewById(R.id.tvWatt)
        tvBatVolt = findViewById(R.id.tvBatVolt)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvPpsVolt = findViewById(R.id.tvPpsVolt)
        tvProtocol = findViewById(R.id.tvProtocol)
        tvCapacity = findViewById(R.id.tvCapacity)
        tvTemp = findViewById(R.id.tvTemp)
        tvSource = findViewById(R.id.tvSource)
        tvHealth = findViewById(R.id.tvHealth)
        tvCycles = findViewById(R.id.tvCycles)
        tvTech = findViewById(R.id.tvTech)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}

        btnShizuku.setOnClickListener {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(100)
                }
            } catch (_: Exception) {}
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitchChange) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    ignoreSwitchChange = true
                    switchOverlay.isChecked = false
                    ignoreSwitchChange = false
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val intent = Intent(this, FloatingOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            } else {
                stopService(Intent(this, FloatingOverlayService::class.java))
            }
            ChargingTileService.requestTileRefresh(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}
    }

    private fun updateUI() {
        val info = BatteryHelper.readBatteryInfo(this)

        tvModeBadge.text = info.accessMode
        tvModeBadge.setTextColor(
            when {
                info.accessMode.startsWith("ROOT") -> Color.parseColor("#00E676")
                info.accessMode.startsWith("SHIZUKU") -> Color.parseColor("#4FC3F7")
                else -> Color.parseColor("#FFB74D")
            }
        )

        val showShizukuBtn = try {
            !BatteryHelper.hasRootAccess() && Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        btnShizuku.visibility = if (showShizukuBtn) View.VISIBLE else View.GONE

        if (switchOverlay.isChecked != FloatingOverlayService.isRunning) {
            ignoreSwitchChange = true
            switchOverlay.isChecked = FloatingOverlayService.isRunning
            ignoreSwitchChange = false
        }

        tvStatus.text = info.statusText
        tvWatt.text = info.wattText
        tvWatt.setTextColor(
            if (info.isCharging) Color.parseColor("#00E676") else Color.parseColor("#FFB74D")
        )
        tvBatVolt.text = info.batteryVoltText
        tvCurrent.text = info.currentText
        tvPpsVolt.text = info.ppsVoltText
        tvProtocol.text = info.protocolText
        tvCapacity.text = info.capacityText
        tvTemp.text = info.tempText
        tvSource.text = info.sourceText
        tvHealth.text = info.healthText
        tvCycles.text = info.cyclesText
        tvTech.text = info.techText
    }
}
