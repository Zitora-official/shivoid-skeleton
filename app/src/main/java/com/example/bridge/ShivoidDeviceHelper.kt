package com.example.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

class ShivoidDeviceHelper(private val context: Context) {

    fun getBatteryInfoJson(): String {
        val root = JSONObject()
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)

            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) {
                    ((level / scale.toFloat()) * 100).toInt()
                } else {
                    -1
                }

                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val plugType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Battery"
                }

                val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val tempCelsius = if (temp > 0) temp / 10.0 else 0.0

                root.put("level", batteryPct)
                root.put("isCharging", isCharging)
                root.put("chargingType", plugType)
                root.put("temperatureCelsius", tempCelsius)
            } else {
                root.put("level", 100)
                root.put("isCharging", false)
                root.put("chargingType", "Unknown")
            }
        } catch (e: Exception) {
            root.put("error", e.message ?: "Unknown battery error")
        }
        return root.toString()
    }

    fun getDeviceInfoJson(): String {
        val root = JSONObject()
        try {
            root.put("appName", "SHI.V01D")
            root.put("appVersion", "1.0")
            root.put("developer", "SHIVANSH THAKUR")
            root.put("deviceModel", Build.MODEL)
            root.put("model", Build.MODEL)
            root.put("manufacturer", Build.MANUFACTURER)
            root.put("brand", Build.BRAND)
            root.put("androidVersion", Build.VERSION.RELEASE)
            root.put("apiLevel", Build.VERSION.SDK_INT)

            // Screen
            val dm = context.resources.displayMetrics
            val screenObj = JSONObject()
            screenObj.put("width", dm.widthPixels)
            screenObj.put("height", dm.heightPixels)
            screenObj.put("density", dm.density)
            screenObj.put("densityDpi", dm.densityDpi)
            root.put("screen", screenObj)

            // Network
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val capabilities = cm?.getNetworkCapabilities(activeNetwork)
            val isOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val networkType = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> "NONE"
            }
            root.put("isOnline", isOnline)
            root.put("networkType", networkType)
        } catch (e: Exception) {
            root.put("error", e.message ?: "Unknown device info error")
        }
        return root.toString()
    }
}
