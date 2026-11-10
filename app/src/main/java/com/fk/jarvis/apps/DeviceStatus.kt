package com.fk.jarvis.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs

/** Telefonun anlık durumu (pil, sıcaklık, bellek, depolama, ağ) — tamamı cihazdan okunur, hiçbir yere gönderilmez. */
object DeviceStatus {
    data class Snapshot(
        val batteryPct: Int?,
        val charging: Boolean,
        val batteryTempC: Float?,
        val ramAvailMb: Long,
        val ramTotalMb: Long,
        val storageFreeGb: Double,
        val storageTotalGb: Double,
        val network: String,
        val powerSave: Boolean,
        val thermal: String?,
    )

    fun read(context: Context): Snapshot {
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val fs = StatFs(Environment.getDataDirectory().path)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "bağlantı yok"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "kablolu"
            else -> "bağlı"
        }
        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> null
            PowerManager.THERMAL_STATUS_MODERATE -> "orta düzeyde ısınmış"
            else -> "çok ısınmış"
        } else null
        return Snapshot(
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempC = if (temp != Int.MIN_VALUE) temp / 10f else null,
            ramAvailMb = mi.availMem / 1_048_576, ramTotalMb = mi.totalMem / 1_048_576,
            storageFreeGb = fs.availableBytes / 1e9, storageTotalGb = fs.totalBytes / 1e9,
            network = net, powerSave = pm.isPowerSaveMode, thermal = thermal,
        )
    }

    fun describe(s: Snapshot): String = buildString {
        s.batteryPct?.let { append("Pil yüzde $it") ; append(if (s.charging) ", şarj oluyor" else "") ; append(". ") }
        s.batteryTempC?.let { append("Pil sıcaklığı ${"%.0f".format(it)} derece") ; append(if (it >= 42) ", oldukça sıcak. " else ". ") }
        s.thermal?.let { append("Telefon $it. ") }
        append("Kullanılabilir bellek ${"%.1f".format(s.ramAvailMb / 1024.0)} / ${"%.1f".format(s.ramTotalMb / 1024.0)} GB. ")
        append("Boş depolama ${"%.0f".format(s.storageFreeGb)} GB. ")
        append("İnternet: ${s.network}.")
        if (s.powerSave) append(" Güç tasarrufu açık.")
    }
}
