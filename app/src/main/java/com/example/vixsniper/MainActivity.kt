package com.example.vixsniper

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // Declare UI components to use them in different functions
    private lateinit var txtVixValue: TextView
    private lateinit var txtStrategy: TextView
    private lateinit var txtLastUpdate: TextView
    private lateinit var txtSystemStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var inputCash: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize all UI Views
        txtVixValue = findViewById(R.id.txtVixValue)
        txtStrategy = findViewById(R.id.txtStrategy)
        txtLastUpdate = findViewById(R.id.txtLastUpdate)
        txtSystemStatus = findViewById(R.id.txtSystemStatus)
        btnSave = findViewById(R.id.btnSave)
        inputCash = findViewById(R.id.inputCash)

        // 2. Load Saved Data (Cash & State)
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val savedCash = prefs.getFloat("USER_CASH", 10000f)
        inputCash.setText(savedCash.toString())

        // 3. Update the UI immediately based on saved state
        updateDashboard()

        // 4. Button Click Listener
        btnSave.setOnClickListener {
            val cash = inputCash.text.toString().toFloatOrNull() ?: 0f

            // Save Cash & Set "Running" Flag to TRUE
            prefs.edit()
                .putFloat("USER_CASH", cash)
                .putBoolean("IS_RUNNING", true) // <--- This saves the "Active" state
                .apply()

            // Start the Background Worker
            startMonitoring()

            Toast.makeText(this, "Sniper Active! Monitoring started.", Toast.LENGTH_SHORT).show()

            // Refresh UI to show Green "Active" status
            updateDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the dashboard every time you open the app
        // (This picks up the new VIX value if the worker ran while app was closed)
        updateDashboard()
    }

    private fun updateDashboard() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val latestVix = prefs.getFloat("LATEST_VIX", 0.0f)
        val isRunning = prefs.getBoolean("IS_RUNNING", false)
        val cash = prefs.getFloat("USER_CASH", 10000f)

        // --- 1. SYSTEM STATUS LINE ---
        if (isRunning) {
            txtSystemStatus.text = "✅ SYSTEM ACTIVE (Capital: $$cash)"
            txtSystemStatus.setBackgroundColor(Color.parseColor("#E8F5E9")) // Light Green Background
            txtSystemStatus.setTextColor(Color.parseColor("#2E7D32")) // Dark Green Text
            btnSave.text = "UPDATE SETTINGS"
        } else {
            txtSystemStatus.text = "❌ SYSTEM IDLE (Not Monitoring)"
            txtSystemStatus.setBackgroundColor(Color.parseColor("#FFEBEE")) // Light Red Background
            txtSystemStatus.setTextColor(Color.parseColor("#C62828")) // Dark Red Text
            btnSave.text = "ACTIVATE SNIPER"
        }

        // --- 2. VIX VALUE DISPLAY ---
        if (latestVix > 0) {
            txtVixValue.text = latestVix.toString()
            txtLastUpdate.text = "Monitoring active. Updates hourly."
        } else {
            txtVixValue.text = "--.--"
            txtLastUpdate.text = "Waiting for first scan..."
        }

        // --- 3. STRATEGY TEXT ---
        if (latestVix >= 45.0) {
            txtStrategy.text = "☢️ CRISIS MODE: BUY HEAVY"
            txtStrategy.setTextColor(Color.RED)
        } else if (latestVix >= 30.0) {
            txtStrategy.text = "🚨 PANIC: BUY MODERATE"
            txtStrategy.setTextColor(Color.parseColor("#D84315")) // Dark Orange
        } else if (latestVix >= 25.0) {
            txtStrategy.text = "⚠️ CORRECTION: START BUYING"
            txtStrategy.setTextColor(Color.parseColor("#F9A825")) // Dark Yellow
        } else {
            txtStrategy.text = "MARKET CALM (WAIT)"
            txtStrategy.setTextColor(Color.GRAY)
        }
    }

    private fun startMonitoring() {
        // 1. Schedule the 15-minute loop (Maximum allowed speed)
        val periodicRequest = PeriodicWorkRequestBuilder<VixWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VixMonitorWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        // 2. Optional: Instant check on button click so you don't wait 15 mins for the FIRST number
        val instantRequest = OneTimeWorkRequest.Builder(VixWorker::class.java).build()
        WorkManager.getInstance(this).enqueue(instantRequest)
    }
}