package com.powerplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object CoverFx {

    fun blurredBackground(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val down = maxOf(80, side / 8)
        val w = maxOf(1, down)
        val h = maxOf(1, (src.height.toFloat() / src.width * w).toInt())

        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val upW = w * 4
        val upH = h * 4
        val big = Bitmap.createScaledBitmap(small, upW, upH, true)

        val result = Bitmap.createBitmap(upW, upH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(big, 0f, 0f, paint)
        canvas.drawColor(android.graphics.Color.argb(160, 0, 0, 0))
        return result
    }
}
