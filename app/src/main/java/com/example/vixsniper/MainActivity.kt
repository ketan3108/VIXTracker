package com.example.vixsniper

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import java.util.concurrent.TimeUnit
import android.view.ViewGroup
import android.view.Gravity

class MainActivity : AppCompatActivity() {

    // UI Variables
    private lateinit var txtVixValue: TextView
    private lateinit var txtVixChange: TextView
    private lateinit var txtSpyValue: TextView
    private lateinit var txtSpyChange: TextView
    private lateinit var txtSsoValue: TextView
    private lateinit var txtSsoChange: TextView

    private lateinit var txtStrategy: TextView
    private lateinit var txtLastUpdate: TextView
    private lateinit var txtSystemStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var inputCash: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnSettings: ImageView
    private lateinit var btnHistory: ImageView

    private var isManualUpdate = false

    private val liveDataListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "LATEST_VIX") {
            runOnUiThread {
                updateDashboard()
                if (!isManualUpdate) Toast.makeText(this, "Data Refreshed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init Views
        txtVixValue = findViewById(R.id.txtVixValue)
        txtVixChange = findViewById(R.id.txtVixChange)
        txtSpyValue = findViewById(R.id.txtSpyValue)
        txtSpyChange = findViewById(R.id.txtSpyChange)
        txtSsoValue = findViewById(R.id.txtSsoValue)
        txtSsoChange = findViewById(R.id.txtSsoChange)

        txtStrategy = findViewById(R.id.txtStrategy)
        txtLastUpdate = findViewById(R.id.txtLastUpdate)
        txtSystemStatus = findViewById(R.id.txtSystemStatus)
        btnSave = findViewById(R.id.btnSave)
        inputCash = findViewById(R.id.inputCash)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        btnSettings = findViewById(R.id.btnSettings)
        btnHistory = findViewById(R.id.btnHistory)

        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        inputCash.setText(prefs.getFloat("USER_CASH", 10000f).toString())

        updateDashboard()

        // 1. UPDATE / ACTIVATE BUTTON
        btnSave.setOnClickListener {
            val cash = inputCash.text.toString().toFloatOrNull() ?: 0f
            prefs.edit().putFloat("USER_CASH", cash).putBoolean("IS_RUNNING", true).apply()
            startMonitoring()
            Toast.makeText(this, "Sniper Active! 🎯", Toast.LENGTH_SHORT).show()
            updateDashboard()
        }

        // 2. SWIPE REFRESH
        swipeRefresh.setOnRefreshListener { forceUpdateVix() }

        // 3. SETTINGS BUTTON (Dynamic Strategy)
        btnSettings.setOnClickListener { showSettingsDialog() }

        // 4. HISTORY BUTTON (Audit Log)
        btnHistory.setOnClickListener { showAuditLog() }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(liveDataListener)

        // NEW: If data is older than 16 minutes, force an update immediately
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val lastTime = prefs.getLong("LAST_UPDATE_TIME", 0)
        val timeDiff = System.currentTimeMillis() - lastTime

        if (timeDiff > (16 * 60 * 1000)) {
            Toast.makeText(this, "Data stale. Forcing update...", Toast.LENGTH_SHORT).show()
            forceUpdateVix()
        } else {
            updateDashboard()
        }
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(liveDataListener)
    }

    private fun updateDashboard() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val vix = prefs.getFloat("LATEST_VIX", 0.0f)
        val vixChg = prefs.getFloat("LATEST_CHANGE", 0.0f)

        val spy = prefs.getFloat("LATEST_SPY", 0.0f)
        val spyChg = prefs.getFloat("CHANGE_SPY", 0.0f)

        val sso = prefs.getFloat("LATEST_SSO", 0.0f)
        val ssoChg = prefs.getFloat("CHANGE_SSO", 0.0f)

        val lastTime = prefs.getLong("LAST_UPDATE_TIME", 0)
        val isRunning = prefs.getBoolean("IS_RUNNING", false)

        // Status
        if (isRunning) {
            txtSystemStatus.text = "● SYSTEM ACTIVE"
            txtSystemStatus.setTextColor(getColor(R.color.vix_green))
        } else {
            txtSystemStatus.text = "● SYSTEM IDLE"
            txtSystemStatus.setTextColor(getColor(R.color.text_secondary))
        }

        // --- UPDATE VIX ---
        if (vix > 0) {
            txtVixValue.text = String.format("%.2f", vix)
            formatChangeText(txtVixChange, vixChg, true) // True = Inverse
        }

        // --- UPDATE SPY ---
        if (spy > 0) {
            txtSpyValue.text = String.format("$%.2f", spy)
            formatChangeText(txtSpyChange, spyChg, false) // False = Normal
        }

        // --- UPDATE SSO ---
        if (sso > 0) {
            txtSsoValue.text = String.format("$%.2f", sso)
            formatChangeText(txtSsoChange, ssoChg, false)
        }

        // Time
        if (lastTime > 0) {
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
            txtLastUpdate.text = "Last Sync: " + sdf.format(java.util.Date(lastTime))
        }

        // Strategy Display
        val lCrisis = prefs.getFloat("THRESHOLD_CRISIS", 45.0f)
        val lPanic = prefs.getFloat("THRESHOLD_PANIC", 30.0f)
        val lCorr = prefs.getFloat("THRESHOLD_CORRECTION", 25.0f)

        if (vix >= lCrisis) {
            txtStrategy.text = "☢️ CRISIS (>$lCrisis): BUY SSO AGGRESSIVELY"
            txtStrategy.setTextColor(getColor(R.color.vix_red))
        } else if (vix >= lPanic) {
            txtStrategy.text = "🚨 PANIC (>$lPanic): BUY SSO MODERATE"
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
        } else if (vix >= lCorr) {
            txtStrategy.text = "⚠️ CORRECTION (>$lCorr): BUY SSO LIGHT"
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
        } else {
            txtStrategy.text = "MARKET CALM: WAIT (Trigger >$lCorr)"
            txtStrategy.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun formatChangeText(view: TextView, value: Float, isInverse: Boolean) {
        val sign = if (value >= 0) "+" else ""
        view.text = String.format("%s%.2f%%", sign, value)

        val green = getColor(R.color.vix_green)
        val red = getColor(R.color.vix_red)

        if (isInverse) {
            view.setTextColor(if (value >= 0) red else green)
        } else {
            view.setTextColor(if (value >= 0) green else red)
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun addInput(title: String, key: String, def: Float): EditText {
            val label = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat(key, def).toString())
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            layout.addView(label)
            layout.addView(input)
            return input
        }

        val inCrisis = addInput("Crisis Threshold (Tier 3)", "THRESHOLD_CRISIS", 45.0f)
        val inPanic = addInput("Panic Threshold (Tier 2)", "THRESHOLD_PANIC", 30.0f)
        val inCorr = addInput("Correction Threshold (Tier 1)", "THRESHOLD_CORRECTION", 25.0f)

        AlertDialog.Builder(this)
            .setTitle("Strategy Engine")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                prefs.edit()
                    .putFloat("THRESHOLD_CRISIS", inCrisis.text.toString().toFloatOrNull() ?: 45.0f)
                    .putFloat("THRESHOLD_PANIC", inPanic.text.toString().toFloatOrNull() ?: 30.0f)
                    .putFloat("THRESHOLD_CORRECTION", inCorr.text.toString().toFloatOrNull() ?: 25.0f)
                    .apply()
                updateDashboard()
                Toast.makeText(this, "Strategy Updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showAuditLog() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val rawLog = prefs.getString("AUDIT_LOG", "No history yet.") ?: ""
        val formattedLog = rawLog.replace("|", "\n\n")

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val textView = TextView(this).apply {
            text = formattedLog
            setPadding(50, 50, 50, 50)
            textSize = 12f
            setTextColor(Color.DKGRAY)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("System Audit Log")
            .setView(scrollView)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun startMonitoring() {
        // 1. Remove strict constraints (Let it run even on weak battery)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 2. Define the work
        val workRequest = PeriodicWorkRequestBuilder<VixWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        // 3. THE FIX: Change 'UPDATE' to 'KEEP'
        // 'UPDATE' = Resets timer to 0 (BAD).
        // 'KEEP' = Continues the timer where it left off (GOOD).
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VixMonitorWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun forceUpdateVix() {
        isManualUpdate = true
        val instantRequest = OneTimeWorkRequest.Builder(VixWorker::class.java).build()
        WorkManager.getInstance(this).enqueue(instantRequest)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(instantRequest.id).observe(this) { workInfo ->
            if (workInfo != null && (workInfo.state == WorkInfo.State.SUCCEEDED || workInfo.state == WorkInfo.State.FAILED)) {
                swipeRefresh.isRefreshing = false
                updateDashboard()
                isManualUpdate = false
                if (workInfo.state == WorkInfo.State.SUCCEEDED) Toast.makeText(this, "Market Data Updated", Toast.LENGTH_SHORT).show()
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (swipeRefresh.isRefreshing) {
                swipeRefresh.isRefreshing = false
                isManualUpdate = false
            }
        }, 8000)
    }
}