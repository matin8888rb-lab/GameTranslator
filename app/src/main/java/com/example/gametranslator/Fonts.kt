package com.example.gametranslator

import android.content.Context
import android.graphics.Typeface

/**
 * Loads assets/fonts/font.ttf (Vazirmatn, downloaded by the GitHub workflow).
 * If the file is missing the system font is used.
 */
object Fonts {
    private var cached: Typeface? = null
    private var tried = false

    fun load(ctx: Context): Typeface {
        if (!tried) {
            tried = true
            cached = try {
                Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/font.ttf")
            } catch (e: Exception) {
                null
            }
        }
        return cached ?: Typeface.DEFAULT
    }

    fun bold(ctx: Context): Typeface = Typeface.create(load(ctx), Typeface.BOLD)
}
