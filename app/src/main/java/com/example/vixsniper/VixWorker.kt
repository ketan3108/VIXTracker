package com.example.vixsniper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VixWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val client = OkHttpClient()

        try {
            // 1. Fetch Data for VIX, SPY, and SSO
            // We use helper function to keep code clean
            val vixData = fetchTicker(client, "^VIX")
            val spyData = fetchTicker(client, "SPY")
            val ssoData = fetchTicker(client, "SSO")

            if (vixData == null || spyData == null || ssoData == null) {
                logAudit(prefs, "ERROR", "Failed to fetch market data")
                return Result.failure()
            }

            // 2. Read Dynamic Strategy Settings
            val levelCrisis = prefs.getFloat("THRESHOLD_CRISIS", 45.0f)
            val levelPanic = prefs.getFloat("THRESHOLD_PANIC", 30.0f)
            val levelCorrection = prefs.getFloat("THRESHOLD_CORRECTION", 25.0f)
            val totalCash = prefs.getFloat("USER_CASH", 10000f)

            // 3. Analyze Strategy
            val currentVix = vixData.first
            val tier1 = (totalCash * 0.30).toInt()
            val tier2 = (totalCash * 0.30).toInt()
            val tier3 = (totalCash * 0.40).toInt()

            val (newZone, message) = when {
                currentVix >= levelCrisis -> 3 to "☢️ CRISIS (VIX $currentVix). BUY SSO $$tier3!"
                currentVix >= levelPanic -> 2 to "🚨 PANIC (VIX $currentVix). BUY SSO $$tier2."
                currentVix >= levelCorrection -> 1 to "⚠️ CORRECTION (VIX $currentVix). BUY SSO $$tier1."
                currentVix <= 12.0 -> -1 to "✅ CALM (VIX $currentVix). Sell SSO/Profit."
                else -> 0 to "Sleep mode. VIX is $currentVix."
            }

            // 4. Save ALL Data (Market Context + Audit)
            prefs.edit()
                // VIX
                .putFloat("LATEST_VIX", vixData.first.toFloat())
                .putFloat("LATEST_CHANGE", vixData.second.toFloat())
                // SPY
                .putFloat("LATEST_SPY", spyData.first.toFloat())
                .putFloat("CHANGE_SPY", spyData.second.toFloat())
                // SSO
                .putFloat("LATEST_SSO", ssoData.first.toFloat())
                .putFloat("CHANGE_SSO", ssoData.second.toFloat())
                // Meta
                .putLong("LAST_UPDATE_TIME", System.currentTimeMillis())
                .putString("LAST_ERROR", "No Error")
                .apply()

            val lastNotifiedZone = prefs.getInt("LAST_ZONE", 0)

            // Always log the check so you have history
            logAudit(prefs, "CHECK", "VIX:$currentVix | Zone:$newZone | LastZone:$lastNotifiedZone")

            // --- RE-ENABLED ANTI-SPAM FILTER ---
            // Only alert if the zone CHANGED (e.g., Calm -> Warning)
            if (newZone != lastNotifiedZone && newZone != 0) {
                sendNotification(currentVix, message)
                logAudit(prefs, "ALERT", "Sent Notification: $message")
                prefs.edit().putInt("LAST_ZONE", newZone).apply()
            }

            return Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            logAudit(prefs, "CRASH", e.message ?: "Unknown Error")
            return Result.failure()
        }
    }

    // --- HELPER 1: Fetch Any Ticker ---
    private fun fetchTicker(client: OkHttpClient, symbol: String): Pair<Double, Double>? {
        try {
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=1d")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val jsonStr = response.body?.string() ?: return null
                val root = JSONObject(jsonStr)
                val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")

                val price = meta.getDouble("regularMarketPrice")
                val prevClose = meta.optDouble("chartPreviousClose", price)

                val changePercent = if (prevClose > 0) ((price - prevClose) / prevClose) * 100 else 0.0
                return Pair(price, changePercent)
            }
        } catch (e: Exception) {
            return null
        }
    }

    // --- HELPER 2: Audit Logger ---
    private fun logAudit(prefs: android.content.SharedPreferences, type: String, msg: String) {
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val newEntry = "[$timestamp] $type: $msg"

        // Get existing log, add new one, keep last 20 entries only
        val currentLog = prefs.getString("AUDIT_LOG", "") ?: ""
        val logList = currentLog.split("|").toMutableList()
        logList.add(0, newEntry) // Add to top
        if (logList.size > 20) logList.removeAt(logList.lastIndex) // Trim old logs

        prefs.edit().putString("AUDIT_LOG", logList.joinToString("|")).apply()
    }

    private fun sendNotification(vix: Double, text: String) {
        val channelId = "VIX_ALERTS"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Vix Sniper Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VIX Alert: $vix")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { manager.notify(1, notification) } catch (e: Exception) { e.printStackTrace() }
    }
}