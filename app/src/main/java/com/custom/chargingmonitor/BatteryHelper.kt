package com.custom.chargingmonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs

data class BatteryInfo(
    val statusText: String,
    val isCharging: Boolean,
    val wattText: String,
    val wattValue: Double,
    val batteryVoltText: String,
    val currentText: String,
    val ppsVoltText: String,
    val ppsShortText: String,
    val protocolText: String,
    val capacityText: String,
    val tempText: String,
    val sourceText: String,
    val healthText: String,
    val cyclesText: String,
    val techText: String,
    val accessMode: String
)

object BatteryHelper {

    private var cachedRoot: Boolean? = null

    fun hasRootAccess(): Boolean {
        cachedRoot?.let { return it }
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = reader.readLine() ?: ""
            process.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
        cachedRoot = result
        return result
    }

    fun hasShizukuAccess(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun runPrivilegedCmd(cmd: String): String? {
        if (hasRootAccess()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val r = BufferedReader(InputStreamReader(p.inputStream))
                val out = r.readText().trim()
                p.waitFor()
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }
        if (hasShizukuAccess()) {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                val r = BufferedReader(InputStreamReader(process.inputStream))
                val out = r.readText().trim()
                process.waitFor()
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }
        return null
    }

    private fun readSysfsNode(paths: List<String>): String? {
        val joined = paths.joinToString(" ")
        val cmd = "for f in $joined; do if [ -r \"\$f\" ]; then cat \"\$f\"; break; fi; done"
        val out = runPrivilegedCmd(cmd)
        if (!out.isNullOrEmpty() && !out.contains("denied", ignoreCase = true)) {
            return out.lines().firstOrNull()?.trim()
        }
        return null
    }

    fun readBatteryInfo(context: Context): BatteryInfo {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }

        val voltMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val batVolt = if (voltMv > 10000) voltMv / 1000000.0 else voltMv / 1000.0

        var rawCurr = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (rawCurr == Int.MIN_VALUE || rawCurr == 0) {
            val sysCurr = readSysfsNode(listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now"
            ))?.toIntOrNull()
            if (sysCurr != null) rawCurr = sysCurr
        }
        val absCurr = abs(rawCurr).toDouble()
        val amp = if (absCurr > 10000.0) absCurr / 1000000.0 else absCurr / 1000.0
        val ma = amp * 1000.0

        val watt = batVolt * amp

        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val sourceText = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Connected" else "Baterai"
        }

        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Good"
        }

        val techText = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

        val mode = when {
            hasRootAccess() -> "ROOT ACCESS (KERNEL)"
            hasShizukuAccess() -> "SHIZUKU ACCESS"
            else -> "NON-ROOT (API)"
        }

        // Cycles
        var cyclesVal: String? = null
        if (Build.VERSION.SDK_INT >= 34 && intent != null) {
            val c = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)
            if (c > 0) cyclesVal = "$c Kali"
        }
        if (cyclesVal == null) {
            val sysCycles = readSysfsNode(listOf(
                "/sys/class/power_supply/battery/cycle_count",
                "/sys/class/power_supply/bms/cycle_count",
                "/sys/class/power_supply/max170xx_battery/cycle_count"
            ))
            val num = sysCycles?.filter { it.isDigit() }
            if (!num.isNullOrEmpty() && num.toIntOrNull() != null && num.toInt() > 0) {
                cyclesVal = "$num Kali"
            }
        }
        val cyclesText = cyclesVal ?: "N/A"

        // PPS / VBUS
        var ppsVoltText = "N/A"
        var ppsShortText = "N/A"
        var protocolText = if (isCharging) "Standard 5V" else "Tidak Mengisi"

        val usbVoltRaw = readSysfsNode(listOf(
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/typec/voltage_now",
            "/sys/class/power_supply/ac/voltage_now",
            "/sys/class/power_supply/main/voltage_now"
        ))
        val usbVoltNum = usbVoltRaw?.filter { it.isDigit() }?.toLongOrNull()
        if (usbVoltNum != null && usbVoltNum > 0) {
            val vbus = if (usbVoltNum > 10000) usbVoltNum / 1000000.0 else usbVoltNum / 1000.0
            if (vbus > 1.0) {
                ppsVoltText = String.format(Locale.US, "%.2f V", vbus)
                ppsShortText = String.format(Locale.US, "%.2f V", vbus)
                protocolText = when {
                    vbus >= 7.5 -> "USB-PD / PPS (High Voltage)"
                    isCharging -> "Standard 5V"
                    else -> "Standby"
                }
            }
        } else if (isCharging && watt >= 16.0) {
            ppsVoltText = "PPS / PD Aktif"
            ppsShortText = "PPS/PD"
            protocolText = "USB-PD / PPS (Estimasi)"
        }

        val usbTypeRaw = readSysfsNode(listOf(
            "/sys/class/power_supply/usb/real_type",
            "/sys/class/power_supply/usb/type"
        ))
        if (!usbTypeRaw.isNullOrEmpty() && usbTypeRaw != "Unknown") {
            protocolText = usbTypeRaw.trim()
        }

        return BatteryInfo(
            statusText = statusText,
            isCharging = isCharging,
            wattText = String.format(Locale.US, "%.2f W", watt),
            wattValue = watt,
            batteryVoltText = String.format(Locale.US, "%.2f V", batVolt),
            currentText = String.format(Locale.US, "%.2f A (%.0f mA)", amp, ma),
            ppsVoltText = ppsVoltText,
            ppsShortText = ppsShortText,
            protocolText = protocolText,
            capacityText = "$capacity%",
            tempText = String.format(Locale.US, "%.1f °C", tempC),
            sourceText = sourceText,
            healthText = healthText,
            cyclesText = cyclesText,
            techText = techText,
            accessMode = mode
        )
    }
}
