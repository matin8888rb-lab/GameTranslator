package com.example.gametranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val reqCapture = 1001
    private lateinit var aiSwitch: Switch
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "مترجم بازی (انگلیسی ← فارسی)"
            textSize = 20f
        })
        aiSwitch = Switch(this).apply {
            text = "ترجمه با هوش مصنوعی (Gemini)"
            isChecked = prefs.getBoolean("ai", false)
        }
        keyField = EditText(this).apply {
            hint = "کلید API جمینی"
            setText(prefs.getString("key", ""))
            setSingleLine()
        }
        modelField = EditText(this).apply {
            hint = "نام مدل"
            setText(prefs.getString("model", "gemini-2.5-flash"))
            setSingleLine()
        }
        val start = Button(this).apply {
            text = "شروع (نمایش دکمه‌ی شناور)"
            setOnClickListener { saveAndStart() }
        }
        val hint = TextView(this).apply {
            text = "بعد از شروع، بازی رو باز کن و دکمه‌ی شناور رو بزن. دوباره بزنی ترجمه پاک می‌شه. بار اول برای دانلود مدل آفلاین اینترنت لازمه."
        }
        listOf(aiSwitch, keyField, modelField, start, hint).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveAndStart() {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putBoolean("ai", aiSwitch.isChecked)
            .putString("key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim().ifEmpty { "gemini-2.5-flash" })
            .apply()

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
