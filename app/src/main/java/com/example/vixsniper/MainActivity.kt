package com.example.vixsniper

import android.content.Context
import android.content.SharedPreferences // Import this!
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var txtVixValue: TextView
    private lateinit var txtVixChange: TextView
    private lateinit var txtStrategy: TextView
    private lateinit var txtLastUpdate: TextView
    private lateinit var txtSystemStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var inputCash: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var isManualUpdate = false

    // 1. DEFINE THE LIVE LISTENER
    // This watches the memory for changes. If "LATEST_VIX" changes, it refreshes the screen.
    private val liveDataListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "LATEST_VIX") {
            runOnUiThread {
                updateDashboard()
                // Only show "Auto" toast if it is NOT a manual update
                if (!isManualUpdate) {
                    Toast.makeText(this, "Auto-Refreshed: New Market Data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        txtVixValue = findViewById(R.id.txtVixValue)
        txtVixChange = findViewById(R.id.txtVixChange)
        txtStrategy = findViewById(R.id.txtStrategy)
        txtLastUpdate = findViewById(R.id.txtLastUpdate)
        txtSystemStatus = findViewById(R.id.txtSystemStatus)
        btnSave = findViewById(R.id.btnSave)
        inputCash = findViewById(R.id.inputCash)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        // Load Data
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        inputCash.setText(prefs.getFloat("USER_CASH", 10000f).toString())

        updateDashboard()

        btnSave.setOnClickListener {
            val cash = inputCash.text.toString().toFloatOrNull() ?: 0f
            prefs.edit()
                .putFloat("USER_CASH", cash)
                .putBoolean("IS_RUNNING", true)
                .apply()

            startMonitoring()
            Toast.makeText(this, "Sniper Active! 🎯", Toast.LENGTH_SHORT).show()
            updateDashboard()
        }

        swipeRefresh.setOnRefreshListener {
            forceUpdateVix()
        }
    }

    override fun onResume() {
        super.onResume()
        // 2. ACTIVATE THE LISTENER
        // When the app is open, start listening for background updates
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(liveDataListener)

        updateDashboard()
    }

    override fun onPause() {
        super.onPause()
        // 3. PAUSE THE LISTENER
        // Stop listening when the app is closed to save battery
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(liveDataListener)
    }

    private fun updateDashboard() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val latestVix = prefs.getFloat("LATEST_VIX", 0.0f)
        val changePercent = prefs.getFloat("LATEST_CHANGE", 0.0f)
        val lastTime = prefs.getLong("LAST_UPDATE_TIME", 0)
        val isRunning = prefs.getBoolean("IS_RUNNING", false)
        val cash = prefs.getFloat("USER_CASH", 10000f)

        // Status Bar
        if (isRunning) {
            txtSystemStatus.text = "● SYSTEM ACTIVE"
            txtSystemStatus.setTextColor(getColor(R.color.vix_green))
        } else {
            txtSystemStatus.text = "● SYSTEM IDLE"
            txtSystemStatus.setTextColor(getColor(R.color.text_secondary))
        }

        // VIX Value & Trend
        if (latestVix > 0) {
            txtVixValue.text = String.format("%.2f", latestVix)
            val sign = if (changePercent >= 0) "+" else ""
            txtVixChange.text = String.format("%s%.2f%%", sign, changePercent)

            if (changePercent >= 0) {
                txtVixChange.setTextColor(getColor(R.color.vix_red))
            } else {
                txtVixChange.setTextColor(getColor(R.color.vix_green))
            }

            if (lastTime > 0) {
                val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                txtLastUpdate.text = "Updated: " + sdf.format(java.util.Date(lastTime))
            }

        } else {
            txtVixValue.text = "--.--"
            txtVixChange.text = ""
            txtLastUpdate.text = "Waiting for sync..."
        }

        // Strategy
        if (latestVix >= 45.0) {
            txtStrategy.text = "☢️ CRISIS MODE: BUY HEAVY"
            txtStrategy.setTextColor(getColor(R.color.vix_red))
        } else if (latestVix >= 30.0) {
            txtStrategy.text = "🚨 PANIC: BUY MODERATE"
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
        } else if (latestVix >= 25.0) {
            txtStrategy.text = "⚠️ CORRECTION: START BUYING"
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
        } else {
            txtStrategy.text = "MARKET CALM (WAIT)"
            txtStrategy.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun startMonitoring() {
        val workRequest = PeriodicWorkRequestBuilder<VixWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VixMonitorWork", ExistingPeriodicWorkPolicy.UPDATE, workRequest
        )
    }

    private fun forceUpdateVix() {
        // 3. SET FLAG TO TRUE (Silence the auto-toast)
        isManualUpdate = true

        val instantRequest = OneTimeWorkRequest.Builder(VixWorker::class.java).build()
        WorkManager.getInstance(this).enqueue(instantRequest)

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(instantRequest.id)
            .observe(this) { workInfo ->
                if (workInfo != null && (workInfo.state == WorkInfo.State.SUCCEEDED || workInfo.state == WorkInfo.State.FAILED)) {
                    swipeRefresh.isRefreshing = false
                    updateDashboard()

                    if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                        Toast.makeText(this, "Updated Successfully! 🎯", Toast.LENGTH_SHORT).show()
                    } else {
                        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
                        val lastError = prefs.getString("LAST_ERROR", "Unknown Failure")
                        Toast.makeText(this, "FAILED: $lastError", Toast.LENGTH_LONG).show()
                    }

                    // 4. RESET FLAG (Allow auto-toasts again)
                    isManualUpdate = false
                }
            }

        // Safety Timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (swipeRefresh.isRefreshing) {
                swipeRefresh.isRefreshing = false
                isManualUpdate = false // Reset here too just in case
                Toast.makeText(this, "Timeout: Connection hung", Toast.LENGTH_SHORT).show()
            }
        }, 8000)
    }
}