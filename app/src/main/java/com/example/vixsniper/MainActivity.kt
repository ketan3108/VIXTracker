package com.example.vixsniper

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // --- UI Variables ---
    private lateinit var txtVixValue: TextView
    private lateinit var txtVixChange: TextView
    private lateinit var txtSpyValue: TextView
    private lateinit var txtSpyChange: TextView
    private lateinit var txtSsoValue: TextView
    private lateinit var txtSsoChange: TextView

    // New UI Elements
    private lateinit var progressFearMeter: ProgressBar
    private lateinit var txtStrategyTitle: TextView
    private lateinit var imgStrategyIcon: ImageView
    private lateinit var txtStrategy: TextView

    private lateinit var txtLastUpdate: TextView
    private lateinit var txtSystemStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var inputCash: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnSettings: ImageView
    private lateinit var btnHistory: ImageView

    private var isManualUpdate = false

    // --- PERMISSION LAUNCHER (Android 13+) ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notifications Allowed ✅", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Notifications Blocked. Alerts won't work.", Toast.LENGTH_LONG).show()
        }
    }

    // --- LIVE DATA LISTENER ---
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

        // --- ASK FOR PERMISSION IMMEDIATELY ---
        askForNotificationPermission()

        // --- Init Views ---
        txtVixValue = findViewById(R.id.txtVixValue)
        txtVixChange = findViewById(R.id.txtVixChange)
        txtSpyValue = findViewById(R.id.txtSpyValue)
        txtSpyChange = findViewById(R.id.txtSpyChange)
        txtSsoValue = findViewById(R.id.txtSsoValue)
        txtSsoChange = findViewById(R.id.txtSsoChange)

        progressFearMeter = findViewById(R.id.progressFearMeter)
        txtStrategyTitle = findViewById(R.id.txtStrategyTitle)
        imgStrategyIcon = findViewById(R.id.imgStrategyIcon)
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

        // 3. SETTINGS BUTTON
        btnSettings.setOnClickListener { showSettingsDialog() }

        // 4. HISTORY BUTTON
        btnHistory.setOnClickListener { showAuditLog() }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Already granted
            } else {
                // Ask for it
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(liveDataListener)

        // Safety Check
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val lastTime = prefs.getLong("LAST_UPDATE_TIME", 0)
        if (System.currentTimeMillis() - lastTime > (16 * 60 * 1000)) {
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
            txtSystemStatus.text = "● LIVE MONITORING"
            txtSystemStatus.setTextColor(getColor(R.color.vix_green))
        } else {
            txtSystemStatus.text = "● SYSTEM PAUSED"
            txtSystemStatus.setTextColor(getColor(R.color.text_secondary))
        }

        // Data
        if (vix > 0) {
            txtVixValue.text = String.format("%.2f", vix)
            formatChangeText(txtVixChange, vixChg, true)
            progressFearMeter.progress = vix.toInt().coerceAtMost(60)
        }
        if (spy > 0) {
            txtSpyValue.text = String.format("$%.2f", spy)
            formatChangeText(txtSpyChange, spyChg, false)
        }
        if (sso > 0) {
            txtSsoValue.text = String.format("$%.2f", sso)
            formatChangeText(txtSsoChange, ssoChg, false)
        }

        // Time
        if (lastTime > 0) {
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
            txtLastUpdate.text = "Synced: " + sdf.format(java.util.Date(lastTime))
        }

        // Strategy
        val lCrisis = prefs.getFloat("THRESHOLD_CRISIS", 45.0f)
        val lPanic = prefs.getFloat("THRESHOLD_PANIC", 30.0f)
        val lCorr = prefs.getFloat("THRESHOLD_CORRECTION", 25.0f)

        if (vix >= lCrisis) {
            txtStrategyTitle.text = "☢️  CRITICAL ALERT"
            txtStrategy.text = "CRISIS DETECTED (VIX > $lCrisis)"
            txtStrategyTitle.setTextColor(getColor(R.color.vix_red))
            txtStrategy.setTextColor(getColor(R.color.vix_red))
            imgStrategyIcon.setColorFilter(getColor(R.color.vix_red))
        }
        else if (vix >= lPanic) {
            txtStrategyTitle.text = "🚨  PANIC ALERT"
            txtStrategy.text = "HEAVY BUYING SIGNAL (VIX > $lPanic)"
            txtStrategyTitle.setTextColor(getColor(R.color.vix_orange))
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
            imgStrategyIcon.setColorFilter(getColor(R.color.vix_orange))
        }
        else if (vix >= lCorr) {
            txtStrategyTitle.text = "⚠️  MARKET CORRECTION"
            txtStrategy.text = "ACCUMULATION ZONE (VIX > $lCorr)"
            txtStrategyTitle.setTextColor(getColor(R.color.vix_orange))
            txtStrategy.setTextColor(getColor(R.color.vix_orange))
            imgStrategyIcon.setColorFilter(getColor(R.color.vix_orange))
        }
        else {
            txtStrategyTitle.text = "✅  SYSTEM STANDBY"
            txtStrategy.text = "Market is Calm. Waiting for setup."
            txtStrategyTitle.setTextColor(getColor(R.color.vix_green))
            txtStrategy.setTextColor(getColor(R.color.text_secondary))
            imgStrategyIcon.setColorFilter(getColor(R.color.vix_green))
        }
    }

    private fun formatChangeText(view: TextView, value: Float, isInverse: Boolean) {
        val sign = if (value >= 0) "+" else ""
        view.text = String.format("%s%.2f%%", sign, value)
        val green = getColor(R.color.vix_green)
        val red = getColor(R.color.vix_red)
        if (isInverse) view.setTextColor(if (value >= 0) red else green)
        else view.setTextColor(if (value >= 0) green else red)
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("VixPrefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        // --- Helper to add inputs ---
        fun addInput(title: String, key: String, def: Float): EditText {
            val label = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.GRAY)
            }
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat(key, def).toString())
                textSize = 18f
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

            // --- TEST BUTTON (Verifies Permissions & Channel) ---
            .setNeutralButton("TEST ALERT") { _, _ ->
                val channelId = "VIX_ALERTS"
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // 1. Create Channel (Required for Android 8+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        channelId,
                        "Vix Sniper Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    manager.createNotificationChannel(channel)
                }

                // 2. Build Notification
                val builder = NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("TEST SUCCESSFUL")
                    .setContentText("Notifications are working perfectly.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)

                // 3. Fire
                try {
                    // Check Permission for Android 13+
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        manager.notify(999, builder.build())
                        Toast.makeText(this, "Signal Sent.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Permission Denied. Please Allow Notifications.", Toast.LENGTH_LONG).show()
                        askForNotificationPermission()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // --- SAVE BUTTON (With Memory Wipe Fix) ---
            .setPositiveButton("SAVE") { _, _ ->
                prefs.edit()
                    .putFloat("THRESHOLD_CRISIS", inCrisis.text.toString().toFloatOrNull() ?: 45.0f)
                    .putFloat("THRESHOLD_PANIC", inPanic.text.toString().toFloatOrNull() ?: 30.0f)
                    .putFloat("THRESHOLD_CORRECTION", inCorr.text.toString().toFloatOrNull() ?: 25.0f)

                    // THE FIX: Reset 'LAST_ZONE' to -1 so the next check IS GUARANTEED to trigger
                    .putInt("LAST_ZONE", -1)
                    .apply()

                Toast.makeText(this, "Strategy Updated & Reset", Toast.LENGTH_SHORT).show()

                // Trigger immediate check to process the new rules
                forceUpdateVix()
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
            setTextColor(Color.WHITE) // FIXED VISIBILITY
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<VixWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VixMonitorWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        val oneTimeKickstart = OneTimeWorkRequest.Builder(VixWorker::class.java)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueue(oneTimeKickstart)
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