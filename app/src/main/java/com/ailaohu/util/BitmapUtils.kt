package com.ailaohu.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream

object BitmapUtils {

    fun toBase64(bitmap: Bitmap, quality: Int = 80): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun bitmapToBase64(bitmap: Bitmap, maxWidth: Int = 720, quality: Int = 70): String {
        val scaledBitmap = scaleBitmap(bitmap, maxWidth)
        val stream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    fun calculateSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        val size = 32
        val thumb1 = Bitmap.createScaledBitmap(bitmap1, size, size, true)
        val thumb2 = Bitmap.createScaledBitmap(bitmap2, size, size, true)
        var samePixels = 0
        val totalPixels = size * size
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (thumb1.getPixel(x, y) == thumb2.getPixel(x, y)) samePixels++
            }
        }
        return samePixels.toFloat() / totalPixels
    }

    fun createOverlayBitmap(width: Int, height: Int, color: Int, alpha: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        return bitmap
    }
}
