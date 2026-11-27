package com.custom.chargingmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class FloatingOverlayService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "charging_overlay_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvWatt: TextView
    private lateinit var tvLine1: TextView
    private lateinit var tvLine2: TextView

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
        handler.post(updateRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Charging Monitor Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Charging Monitor")
            .setContentText("Overlay aktif")
            .setSmallIcon(R.drawable.ic_tile_bolt)
            .build()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141816"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#2A3530"))
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tvStatus = TextView(this).apply {
            text = "⚡ CHARGING"
            setTextColor(Color.parseColor("#B0B8B4"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener {
                stopSelf()
            }
        }

        headerRow.addView(tvStatus)
        headerRow.addView(btnClose)

        tvWatt = TextView(this).apply {
            text = "0.00 W"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(4))
        }

        tvLine1 = TextView(this).apply {
            text = "0.00 V | 0.00 A | PPS: N/A"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        tvLine2 = TextView(this).apply {
            text = "0% | 0.0 °C"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }

        container.addView(headerRow)
        container.addView(tvWatt)
        container.addView(tvLine1)
        container.addView(tvLine2)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(100)
        }

        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = container
        windowManager.addView(container, params)
    }

    private fun updateStats() {
        val info = BatteryHelper.readBatteryInfo(this)
        val ampOnly = info.currentText.substringBefore(" (")
        tvStatus.text = "⚡ ${info.statusText}"
        tvWatt.text = info.wattText
        tvWatt.setTextColor(
            if (info.isCharging) Color.parseColor("#00E676") else Color.parseColor("#FFB74D")
        )
        tvLine1.text = "${info.batteryVoltText} | $ampOnly | PPS: ${info.ppsShortText}"
        tvLine2.text = "${info.capacityText} | ${info.tempText}"
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        ChargingTileService.requestTileRefresh(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
