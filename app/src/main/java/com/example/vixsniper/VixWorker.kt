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
        try {
            // 1. Fetch Real VIX Data
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/%5EVIX?interval=1d&range=1d")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.retry()

                val jsonStr = response.body?.string() ?: return Result.retry()
                val currentVix = parseVixPrice(jsonStr)

                // 2. Load User Settings
                val prefs = applicationContext.getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
                val totalCash = prefs.getFloat("USER_CASH", 10000f)
                val lastNotifiedZone = prefs.getInt("LAST_ZONE", 0)

                // 3. Analyze Strategy (Real Rules)
                val (newZone, message) = analyzeVixStrategy(currentVix, totalCash)

                // --- CRITICAL LOGIC FIX ---

                // A. ALWAYS save the price.
                // This ensures that when you open the app, you see the latest number (e.g., 16.50),
                // even if the Zone hasn't changed.
                prefs.edit().putFloat("LATEST_VIX", currentVix.toFloat()).apply()

                // B. CONDITIONALLY send notification.
                // Only buzz the phone if we moved into a new Danger Zone.
                if (newZone != lastNotifiedZone && newZone != 0) {
                    sendNotification(currentVix, message)
                    prefs.edit().putInt("LAST_ZONE", newZone).apply()
                }

                // (Optional) Print log for your own sanity check when debugging
                println("VIX_WORKER: Price updated to $currentVix. Zone: $newZone")
            }
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }

    private fun analyzeVixStrategy(vix: Double, cash: Float): Pair<Int, String> {
        val tier1Amount = (cash * 0.30).toInt()
        val tier2Amount = (cash * 0.30).toInt()
        val tier3Amount = (cash * 0.40).toInt()

        // REAL STRATEGY THRESHOLDS
        return when {
            vix >= 45.0 -> 3 to "☢️ CRISIS (VIX $vix). Buy $$tier3Amount!"
            vix >= 30.0 -> 2 to "🚨 PANIC (VIX $vix). Buy $$tier2Amount."
            vix >= 25.0 -> 1 to "⚠️ CORRECTION (VIX $vix). Buy $$tier1Amount."
            vix <= 12.0 -> -1 to "✅ CALM (VIX $vix). Consider taking profits."
            else -> 0 to "Sleep mode. VIX is $vix."
        }
    }

    private fun parseVixPrice(json: String): Double {
        val root = JSONObject(json)
        val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        return meta.getDouble("regularMarketPrice")
    }

    private fun sendNotification(vix: Double, text: String) {
        val channelId = "VIX_ALERTS"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Vix Sniper Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Uses your Logo
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