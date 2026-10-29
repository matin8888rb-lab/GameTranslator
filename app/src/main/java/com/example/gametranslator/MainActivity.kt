package com.example.gametranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val reqCapture = 1001

    private lateinit var font: Typeface
    private lateinit var aiSwitch: Switch
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var gameField: EditText
    private lateinit var glossaryField: EditText
    private lateinit var scaleBar: SeekBar
    private lateinit var scaleLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var mainBtn: Button

    private val cBg = 0xFF0E1020.toInt()
    private val cCard = 0xFF181C33.toInt()
    private val cField = 0xFF232845.toInt()
    private val cAccent = 0xFF7C5CFF.toInt()
    private val cDanger = 0xFFE5484D.toInt()
    private val cText = 0xFFF2F3FA.toInt()
    private val cMuted = 0xFF9AA0C0.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create(font, Typeface.BOLD) else font
    }

    private fun inputField(hintText: String, value: String, lines: Int = 1, secret: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(cText)
            setHintTextColor(cMuted)
            typeface = font
            textSize = 14f
            background = rounded(cField, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_LTR   // keys / English terms read left to right
            textDirection = View.TEXT_DIRECTION_LTR
            if (lines == 1) setSingleLine() else {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
            }
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun card(title: String, vararg views: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 18)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(label(title, 15f, cText, true))
        views.forEach {
            c.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        return c
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(14))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        font = Fonts.load(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(18), dp(28), dp(18), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // header
        root.addView(label("مترجم بازی", 28f, cText, true))
        root.addView(label("متن انگلیسی بازی رو همون‌جا فارسی کن", 14f, cMuted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(18) })

        // status + start/stop
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cMuted) }
        }
        statusText = label("", 14f, cText)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
            addView(statusText)
        }
        mainBtn = Button(this).apply {
            setTextColor(cText)
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.create(font, Typeface.BOLD)
            setOnClickListener { if (OverlayService.running) stopTranslator() else saveAndStart() }
        }
        root.addView(card("وضعیت", statusRow, mainBtn))
        root.addView(gap())

        // AI
        aiSwitch = Switch(this).apply {
            text = "ترجمه با هوش مصنوعی (Gemini)"
            setTextColor(cText)
            typeface = font
            isChecked = prefs.getBoolean("ai", false)
        }
        keyField = inputField("کلید API جمینی", prefs.getString("key", "") ?: "", secret = true)
        modelField = inputField("نام مدل", prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        gameField = inputField("اسم بازی (برای ترجمه‌ی بهتر)", prefs.getString("game", "Genshin Impact") ?: "")
        root.addView(card("هوش مصنوعی", aiSwitch, keyField, modelField, gameField))
        root.addView(gap())

        // look of translated text
        val scale = prefs.getInt("scale", 100).coerceIn(70, 140)
        scaleLabel = label("", 13f, cMuted)
        scaleBar = SeekBar(this).apply {
            max = 70
            progress = scale - 70
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateScaleLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        updateScaleLabel()
        root.addView(card("ظاهر متن ترجمه", scaleLabel, scaleBar))
        root.addView(gap())

        // glossary
        glossaryField = inputField(
            "Mora = مورا\nPrimogem = پریموجم", prefs.getString("glossary", "") ?: "", lines = 4
        )
        root.addView(card(
            "واژه‌نامه (هر خط یک اصطلاح)",
            label("اصطلاح‌هایی که همیشه باید یه جور ترجمه بشن. فرمت: English = فارسی", 12f, cMuted),
            glossaryField
        ))
        root.addView(gap())

        // tips
        root.addView(card(
            "راهنما",
            label(
                "• بعد از شروع، بازی رو باز کن و دکمه‌ی شناور رو بزن تا صفحه ترجمه بشه.\n" +
                "• دوباره که بزنی ترجمه‌ها پاک می‌شن.\n" +
                "• روی هر متن ترجمه‌شده بزنی، با هوش مصنوعی دوباره و بهتر ترجمه می‌شه.\n" +
                "• برای حذف دکمه‌ی شناور، ✕ قرمز گوشه‌ی دکمه رو بزن یا از همین‌جا «توقف» رو بزن.\n" +
                "• بار اول برای دانلود مدل آفلاین اینترنت لازمه.",
                13f, cMuted
            ).apply { setLineSpacing(0f, 1.2f) }
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        })
    }

    private fun updateScaleLabel() {
        scaleLabel.text = "اندازه‌ی متن ترجمه: ${70 + scaleBar.progress}٪ (۱۰۰٪ = هم‌اندازه‌ی متن بازی)"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        savePrefs()
        super.onPause()
    }

    private fun refresh() {
        val on = OverlayService.running
        statusText.text = if (on) "فعاله؛ بازی رو باز کن و دکمه‌ی شناور رو بزن" else "خاموشه"
        (statusDot.background as GradientDrawable).setColor(if (on) 0xFF3DDC84.toInt() else cMuted)
        mainBtn.text = if (on) "توقف و حذف دکمه‌ی شناور" else "شروع (نمایش دکمه‌ی شناور)"
        mainBtn.background = rounded(if (on) cDanger else cAccent, 16)
    }

    private fun savePrefs() {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putBoolean("ai", aiSwitch.isChecked)
            .putString("key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim().ifEmpty { "gemini-2.5-flash" })
            .putString("game", gameField.text.toString().trim())
            .putString("glossary", glossaryField.text.toString())
            .putInt("scale", 70 + scaleBar.progress)
            .apply()
    }

    private fun stopTranslator() {
        startService(Intent(this, OverlayService::class.java).setAction("STOP"))
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    private fun saveAndStart() {
        savePrefs()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اجازه‌ی نمایش روی بقیه‌ی برنامه‌ها رو بده، بعد دوباره شروع رو بزن", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(svc)
            Toast.makeText(this, "دکمه‌ی شناور فعال شد. بازی رو باز کن.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
