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

class VixWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)

        try {
            // 1. Setup Client
            val client = OkHttpClient()

            // 2. Build Request (Pretending to be a Browser to avoid 403 blocks)
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/%5EVIX?interval=1d&range=1d")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()

            // 3. Execute Network Call
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = "Server Error: ${response.code} ${response.message}"
                    prefs.edit().putString("LAST_ERROR", errorMsg).apply()
                    return Result.failure()
                }

                val jsonStr = response.body?.string()
                if (jsonStr == null) {
                    prefs.edit().putString("LAST_ERROR", "Empty Response").apply()
                    return Result.failure()
                }

                // 4. Parse JSON
                val root = JSONObject(jsonStr)
                val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")

                // --- CRASH FIX: Manual Math Logic ---
                val currentVix = meta.getDouble("regularMarketPrice")

                // Get the 'Previous Close' safely. If missing, use current price (0% change).
                val previousClose = meta.optDouble("chartPreviousClose", currentVix)

                // Manually calculate % Change: ((Current - Prev) / Prev) * 100
                val changePercent = if (previousClose > 0) {
                    ((currentVix - previousClose) / previousClose) * 100
                } else {
                    0.0
                }
                // ------------------------------------

                // 5. Load User Data
                val totalCash = prefs.getFloat("USER_CASH", 10000f)
                val lastNotifiedZone = prefs.getInt("LAST_ZONE", 0)
                val (newZone, message) = analyzeVixStrategy(currentVix, totalCash)

                // 6. Save Data to Memory (So UI can see it)
                prefs.edit()
                    .putFloat("LATEST_VIX", currentVix.toFloat())
                    .putFloat("LATEST_CHANGE", changePercent.toFloat())
                    .putLong("LAST_UPDATE_TIME", System.currentTimeMillis())
                    .putString("LAST_ERROR", "No Error") // Clear any old errors
                    .apply()

                // 7. Notification Logic (Anti-Spam Filter)
                // Only notify if we moved to a new zone (e.g., from Calm -> Warning)
                if (newZone != lastNotifiedZone && newZone != 0) {
                    sendNotification(currentVix, message)
                    prefs.edit().putInt("LAST_ZONE", newZone).apply()
                }

            }
            return Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            // Save the exact crash reason so you can see it on the phone screen
            val crashMsg = "Crash: ${e.message}"
            prefs.edit().putString("LAST_ERROR", crashMsg).apply()
            return Result.failure()
        }
    }

    private fun analyzeVixStrategy(vix: Double, cash: Float): Pair<Int, String> {
        val tier1Amount = (cash * 0.30).toInt()
        val tier2Amount = (cash * 0.30).toInt()
        val tier3Amount = (cash * 0.40).toInt()

        return when {
            vix >= 45.0 -> 3 to "☢️ CRISIS (VIX $vix). Buy $$tier3Amount!"
            vix >= 30.0 -> 2 to "🚨 PANIC (VIX $vix). Buy $$tier2Amount."
            vix >= 25.0 -> 1 to "⚠️ CORRECTION (VIX $vix). Buy $$tier1Amount."
            vix <= 12.0 -> -1 to "✅ CALM (VIX $vix). Consider taking profits."
            else -> 0 to "Sleep mode. VIX is $vix."
        }
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

        try {
            manager.notify(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}