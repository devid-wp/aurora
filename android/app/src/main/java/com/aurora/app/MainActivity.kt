package com.aurora.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder launcher activity. It only proves that the Rust core can be
 * loaded and called from the Android app; real UI comes later.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Aurora v${AuroraCore.version()} — Rust core linked successfully"
        }
        setContentView(text)
    }
}