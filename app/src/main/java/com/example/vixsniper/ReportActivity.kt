package com.example.vixsniper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        // Optional: Set status bar color to match the dark theme perfectly
        window.statusBarColor = getColor(R.color.bg_app)
    }
}