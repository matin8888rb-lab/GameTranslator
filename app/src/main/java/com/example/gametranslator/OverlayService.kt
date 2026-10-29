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

    companion object {
        /** True while the floating button is on screen (read by MainActivity). */
        @Volatile var running = false
    }

    private data class Item(
        val rect: Rect,
        val lines: Int,
        val text: String,
        var bg: Int = Color.BLACK,   // background colour sampled from the screenshot
        var fg: Int = Color.WHITE    // text colour sampled from the screenshot
    )

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var curW = 0
    private var curH = 0
    private var button: View? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private val resultViews = mutableListOf<View>()
    private var currentTexts: List<String> = emptyList()
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
        running = true
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
            .setContentText("دکمه‌ی شناور فعاله. برای حذفش «توقف» رو بزن.")
            .setSmallIcon(R.drawable.ic_stat)
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
        val box = dp(64)
        val size = dp(52)
        val root = FrameLayout(this)

        val circle = TextView(this).apply {
            text = "ف"
            setTextColor(Color.WHITE)
            typeface = Fonts.bold(this@OverlayService)
            gravity = Gravity.CENTER
            textSize = 22f
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF4F46E5.toInt(), 0xFFA855F7.toInt())
            ).apply { shape = GradientDrawable.OVAL }
            alpha = 0.92f
        }
        root.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))

        // small red ✕ on the corner: removes the floating button (stops the service)
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 10f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE5484D.toInt())
            }
            setOnClickListener { stopSelf() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))

        val lp = WindowManager.LayoutParams(
            box, box, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(120) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        circle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(root, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(root, lp)
        button = root; buttonLp = lp
    }

    private fun onTap() {
        if (resultViews.isNotEmpty()) { removeResults(); return }
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

    // ---------- colour sampling (so the translation looks like the original) ----------
    private fun dominant(px: List<Int>): Int {
        val m = HashMap<Int, IntArray>()
        for (p in px) {
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val a = m.getOrPut(k) { IntArray(4) }
            a[0]++; a[1] += r; a[2] += g; a[3] += b
        }
        val best = m.values.maxByOrNull { it[0] }!!
        return Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    private fun dist(a: Int, b: Int) =
        Math.abs(Color.red(a) - Color.red(b)) +
            Math.abs(Color.green(a) - Color.green(b)) +
            Math.abs(Color.blue(a) - Color.blue(b))

    private fun lum(c: Int) =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** Returns (background, text) colours of the area under [r]. */
    private fun sampleColors(bmp: Bitmap, r: Rect): Pair<Int, Int> {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val rr = r.right.coerceIn(l + 1, bmp.width)
        val bb = r.bottom.coerceIn(t + 1, bmp.height)
        val sx = ((rr - l) / 48).coerceAtLeast(1)
        val sy = ((bb - t) / 16).coerceAtLeast(1)
        val pixels = ArrayList<Int>()
        var y = t
        while (y < bb) {
            var x = l
            while (x < rr) { pixels.add(bmp.getPixel(x, y)); x += sx }
            y += sy
        }
        if (pixels.isEmpty()) return Color.BLACK to Color.WHITE
        val bg = dominant(pixels)
        val far = pixels.filter { dist(it, bg) > 150 }
        val safe = if (lum(bg) > 0.5f) Color.BLACK else Color.WHITE
        var fg = if (far.size >= 3) dominant(far) else safe
        if (Math.abs(lum(fg) - lum(bg)) < 0.35f) fg = safe
        return bg to fg
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
            items.forEach { item ->
                val (b, f) = sampleColors(bmp, item.rect)
                item.bg = b; item.fg = f
            }

            val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val texts = items.map { it.text }
            val glossary = Translator.parseGlossary(prefs.getString("glossary", "") ?: "")
            val game = prefs.getString("game", "") ?: ""
            val raw = try {
                val key = prefs.getString("key", "") ?: ""
                if (prefs.getBoolean("ai", false) && key.isNotBlank())
                    Translator.gemini(texts, key, prefs.getString("model", "gemini-2.5-flash")!!, game, glossary)
                else Translator.mlkit(texts)
            } catch (e: Exception) {
                toast("هوش مصنوعی جواب نداد، با ترجمه‌ی آفلاین ادامه می‌دم")
                Translator.mlkit(texts)
            }
            val translated = Translator.applyGlossary(texts, raw, glossary)
            currentTexts = texts
            main.post { showResults(items, translated) }
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        } finally {
            busy.set(false)
            main.post { button?.visibility = View.VISIBLE }
        }
    }

    // ---------- result overlay ----------
    // One small window per text block, so touches outside the blocks still reach the game.
    private fun showResults(items: List<Item>, translated: List<String>) {
        removeResults()
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val scale = prefs.getInt("scale", 100) / 100f
        val (sw, _) = screenSize()
        items.forEachIndexed { i, item -> addBox(item, translated[i], sw, scale) }

        // keep the button above the translations
        button?.let { b -> runCatching { wm.removeView(b); wm.addView(b, buttonLp) } }
    }

    private fun addBox(item: Item, translatedText: String, sw: Int, scale: Float) {
        val grow = dp(3)
        val rect = Rect(item.rect).apply { inset(-grow, -grow) }
        val w = rect.width().coerceAtLeast(dp(60)).coerceAtMost(sw)
        val h = rect.height().coerceAtLeast(dp(20))

        // original line height ≈ font size * 1.25, so start from the same size as the game's text
        val lineH = item.rect.height().toFloat() / item.lines
        val minPx = dp(8)
        val maxPx = (lineH / 1.25f * scale).toInt().coerceAtLeast(minPx + 1)

        val tv = TextView(this).apply {
            this.text = translatedText
            setTextColor(item.fg)
            typeface = Fonts.load(this@OverlayService)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor((item.bg and 0x00FFFFFF) or 0xF2000000.toInt())
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = (if (item.lines >= 2) Gravity.END else Gravity.CENTER_HORIZONTAL) or Gravity.CENTER_VERTICAL
            setLineSpacing(0f, 1.1f)
            setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
            setOnClickListener { fixTranslation(item, this) }
        }

        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left.coerceIn(0, (sw - w).coerceAtLeast(0))
            y = rect.top
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(tv, lp)
        resultViews.add(tv)
    }

    /** Tap on a translated block → ask the AI for a better translation of just that block. */
    private fun fixTranslation(item: Item, tv: TextView) {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) {
            toast("برای اصلاح ترجمه، کلید API جمینی رو توی برنامه وارد کن")
            return
        }
        val old = tv.text.toString()
        tv.text = "…"
        val glossary = Translator.parseGlossary(prefs.getString("glossary", "") ?: "")
        val game = prefs.getString("game", "") ?: ""
        val model = prefs.getString("model", "gemini-2.5-flash")!!
        val ctx = currentTexts.filter { it != item.text }
        worker.execute {
            try {
                val better = Translator.gemini(
                    listOf(item.text), key, model, game, glossary,
                    previous = listOf(old), context = ctx
                )
                main.post { tv.text = better[0] }
            } catch (e: Exception) {
                toast("اصلاح ترجمه انجام نشد")
                main.post { tv.text = old }
            }
        }
    }

    private fun removeResults() {
        resultViews.forEach { v -> runCatching { wm.removeView(v) } }
        resultViews.clear()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        running = false
        if (::wm.isInitialized) {
            removeResults()
            button?.let { runCatching { wm.removeView(it) } }
        }
        vd?.release(); reader?.close()
        projection?.stop()
        recognizer.close()
        worker.shutdown()
        super.onDestroy()
    }
}
