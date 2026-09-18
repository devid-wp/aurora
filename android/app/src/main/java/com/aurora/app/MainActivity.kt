package com.aurora.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val background = Color.rgb(10, 8, 16)
    private val panel = Color.rgb(25, 19, 35)
    private val purple = Color.rgb(184, 116, 255)
    private val primaryText = Color.rgb(247, 243, 252)
    private val secondaryText = Color.rgb(170, 158, 183)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
            setBackgroundColor(background)
        }

        content.addView(label("AURORA", 13, purple, true), width(-1, 28))
        content.addView(label("Your music,\nyour atmosphere.", 32, primaryText, true), width(-1, 104))
        content.addView(label("A quiet place for the things you want to hear.", 15, secondaryText, false), width(-1, 42))

        content.addView(sectionTitle("LIBRARY"), width(-1, 38))
        content.addView(card("Your library", "Albums, artists and saved music will live here."), width(-1, 84))

        content.addView(sectionTitle("DISCOVER"), width(-1, 54))
        content.addView(card("Search", "Find something to listen to when you are ready."), width(-1, 84))

        content.addView(sectionTitle("NOW PLAYING"), width(-1, 54))
        content.addView(card("Nothing playing", "Choose a song and it will appear here."), width(-1, 84))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        }
        setContentView(scroll)
    }

    private fun sectionTitle(text: String) = label(text, 12, secondaryText, true).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(0, 0, 0, dp(10))
    }

    private fun card(title: String, description: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        background = rounded(panel, 16)
        addView(label(title, 17, primaryText, true), width(-1, 28))
        addView(label(description, 13, secondaryText, false), width(-1, 24))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size.toFloat()
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        letterSpacing = if (size <= 13) 0.08f else 0f
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun width(value: Int, height: Int) = LinearLayout.LayoutParams(value, dp(height))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
