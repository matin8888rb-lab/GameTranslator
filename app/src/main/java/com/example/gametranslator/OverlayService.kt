package com.example.gametranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    private data class Item(val rect: Rect, val lines: Int, val text: String)

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var curW = 0
    private var curH = 0
    private var button: View? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var resultLayer: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
        }
        showButton()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tr", "Translator", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "tr")
            .setContentTitle("مترجم بازی")
            .setContentText("دکمه‌ی شناور فعاله")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_delete), "توقف", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    // ---------- floating button ----------
    private fun showButton() {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "فا"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1565C0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(120) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(tv, lp)
        button = tv; buttonLp = lp
    }

    private fun onTap() {
        if (resultLayer != null) { removeResults(); return }
        if (!busy.compareAndSet(false, true)) return
        val (w, h) = screenSize()
        ensureDisplay(w, h)
        button?.visibility = View.INVISIBLE
        main.postDelayed({ worker.execute { process() } }, 350)
    }

    // ---------- capture ----------
    private fun ensureDisplay(w: Int, h: Int) {
        if (vd != null && w == curW && h == curH) return
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val dpi = resources.displayMetrics.densityDpi
        if (vd == null) {
            vd = projection!!.createVirtualDisplay(
                "cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
        } else {
            vd!!.resize(w, h, dpi)
            vd!!.surface = r.surface
        }
        reader = r; curW = w; curH = h
    }

    private fun grabBitmap(): Bitmap? {
        var img: Image? = null
        repeat(20) {
            if (img == null) {
                img = reader?.acquireLatestImage()
                if (img == null) Thread.sleep(100)
            }
        }
        val i = img ?: return null
        try {
            val plane = i.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * i.width
            val full = Bitmap.createBitmap(
                i.width + rowPadding / plane.pixelStride, i.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(full, 0, 0, i.width, i.height)
        } finally { i.close() }
    }

    // ---------- OCR + translate ----------
    private fun process() {
        try {
            val bmp = grabBitmap()
            if (bmp == null) { toast("تصویر صفحه گرفته نشد"); return }

            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val items = result.textBlocks.mapNotNull { b ->
                val r = b.boundingBox ?: return@mapNotNull null
                val t = b.text.replace('\n', ' ').trim()
                if (t.length < 2) null else Item(r, b.lines.size.coerceAtLeast(1), t)
            }
            if (items.isEmpty()) { toast("متنی پیدا نشد"); return }

            val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val texts = items.map { it.text }
            val translated = try {
                val key = prefs.getString("key", "") ?: ""
                if (prefs.getBoolean("ai", false) && key.isNotBlank())
                    Translator.gemini(texts, key, prefs.getString("model", "gemini-2.5-flash")!!)
                else Translator.mlkit(texts)
            } catch (e: Exception) {
                toast("هوش مصنوعی جواب نداد، با ترجمه‌ی آفلاین ادامه می‌دم")
                Translator.mlkit(texts)
            }
            main.post { showResults(items, translated) }
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        } finally {
            busy.set(false)
            main.post { button?.visibility = View.VISIBLE }
        }
    }

    // ---------- result overlay ----------
    private fun showResults(items: List<Item>, translated: List<String>) {
        val (sw, _) = screenSize()
        val layer = FrameLayout(this)
        items.forEachIndexed { i, it ->
            val width = it.rect.width().coerceAtLeast(dp(120)).coerceAtMost(sw)
            val tv = TextView(this).apply {
                text = translated[i]
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6101820.toInt())
                setPadding(dp(4), dp(2), dp(4), dp(2))
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.START
                val px = (it.rect.height() / it.lines / 1.4f).coerceIn(dp(11).toFloat(), dp(22).toFloat())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            }
            val lp = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = it.rect.left.coerceAtMost((sw - width).coerceAtLeast(0))
            lp.topMargin = it.rect.top
            layer.addView(tv, lp)
        }
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28)
            wlp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(layer, wlp)
        resultLayer = layer

        // keep the button above the translations
        button?.let { b -> wm.removeView(b); wm.addView(b, buttonLp) }
    }

    private fun removeResults() {
        resultLayer?.let { wm.removeView(it) }
        resultLayer = null
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        removeResults()
        button?.let { runCatching { wm.removeView(it) } }
        vd?.release(); reader?.close()
        projection?.stop()
        recognizer.close()
        worker.shutdown()
        super.onDestroy()
    }
}
