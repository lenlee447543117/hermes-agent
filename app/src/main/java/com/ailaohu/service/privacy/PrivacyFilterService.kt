package com.ailaohu.service.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyFilterService @Inject constructor() {
    companion object {
        private const val TAG = "PrivacyFilter"

        private val PHONE_PATTERN = Regex(
            """(?:1[3-9]\d{9})|(?:0\d{2,3}-?\d{7,8})"""
        )
        private val ID_CARD_PATTERN = Regex(
            """[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]"""
        )
        private val BANK_CARD_PATTERN = Regex(
            """\d{16,19}"""
        )
        private val EMAIL_PATTERN = Regex(
            """[\w.-]+@[\w.-]+\.\w+"""
        )
        private val ADDRESS_PATTERN = Regex(
            """(?:省|市|区|县|路|号|楼|室|栋|单元)\d*"""
        )
    }

    data class PrivacyRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val type: PrivacyType
    )

    enum class PrivacyType {
        PHONE_NUMBER,
        ID_CARD,
        BANK_CARD,
        EMAIL,
        ADDRESS,
        INPUT_FIELD
    }

    fun filterScreenshot(bitmap: Bitmap): Bitmap {
        val regions = detectPrivacyRegions(bitmap)

        if (regions.isEmpty()) {
            Log.d(TAG, "No privacy regions detected")
            return bitmap
        }

        Log.i(TAG, "Detected ${regions.size} privacy regions, applying mask")
        return applyPrivacyMask(bitmap, regions)
    }

    fun detectPrivacyRegions(bitmap: Bitmap): List<PrivacyRegion> {
        val regions = mutableListOf<PrivacyRegion>()

        detectTextBasedRegions(bitmap, regions)
        detectInputFieldRegions(bitmap, regions)

        Log.d(TAG, "Total privacy regions: ${regions.size}")
        return regions
    }

    private fun detectTextBasedRegions(bitmap: Bitmap, regions: MutableList<PrivacyRegion>) {
        val width = bitmap.width
        val height = bitmap.height
        val pixelStep = maxOf(1, width / 360)

        for (y in 0 until height step pixelStep * 4) {
            for (x in 0 until width step pixelStep) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = getBrightness(pixel)

                if (brightness > 200 || brightness < 30) continue

                val isTextLike = isLikelyTextPixel(bitmap, x, y, pixelStep)
                if (isTextLike) {
                    val regionWidth = estimateTextRegionWidth(bitmap, x, y, pixelStep)
                    if (regionWidth > 20) {
                        val estimatedHeight = (regionWidth * 0.3).toInt().coerceIn(12, 40)
                        regions.add(
                            PrivacyRegion(
                                x = x,
                                y = y - estimatedHeight / 2,
                                width = regionWidth,
                                height = estimatedHeight,
                                type = PrivacyType.ADDRESS
                            )
                        )
                    }
                }
            }
        }

        mergeOverlappingRegions(regions)
    }

    private fun detectInputFieldRegions(bitmap: Bitmap, regions: MutableList<PrivacyRegion>) {
        val width = bitmap.width
        val height = bitmap.height
        val pixelStep = maxOf(1, width / 180)

        for (y in height / 3 until height * 2 / 3 step pixelStep * 6) {
            for (x in width / 6 until width * 5 / 6 step pixelStep * 3) {
                val pixel = bitmap.getPixel(x, y)
                val isInputLike = isLikelyInputField(bitmap, x, y, pixelStep)
                if (isInputLike) {
                    val regionWidth = (width * 0.6).toInt()
                    val regionHeight = 50
                    regions.add(
                        PrivacyRegion(
                            x = x - regionWidth / 4,
                            y = y - regionHeight / 2,
                            width = regionWidth,
                            height = regionHeight,
                            type = PrivacyType.INPUT_FIELD
                        )
                    )
                }
            }
        }
    }

    private fun isLikelyTextPixel(bitmap: Bitmap, x: Int, y: Int, step: Int): Boolean {
        if (x < step || x >= bitmap.width - step || y < step || y >= bitmap.height - step) return false

        val center = getBrightness(bitmap.getPixel(x, y))
        val left = getBrightness(bitmap.getPixel(x - step, y))
        val right = getBrightness(bitmap.getPixel(x + step, y))
        val top = getBrightness(bitmap.getPixel(x, y - step))
        val bottom = getBrightness(bitmap.getPixel(x, y + step))

        val hContrast = abs(left - center) + abs(right - center)
        val vContrast = abs(top - center) + abs(bottom - center)

        return hContrast > 80 || vContrast > 80
    }

    private fun estimateTextRegionWidth(bitmap: Bitmap, startX: Int, y: Int, step: Int): Int {
        var endX = startX
        while (endX < bitmap.width - step) {
            val contrast = abs(
                getBrightness(bitmap.getPixel(endX, y)) -
                getBrightness(bitmap.getPixel(endX + step, y))
            )
            if (contrast < 20) break
            endX += step
        }
        return endX - startX
    }

    private fun isLikelyInputField(bitmap: Bitmap, x: Int, y: Int, step: Int): Boolean {
        val width = bitmap.width
        val height = bitmap.height

        if (x < width / 6 || x > width * 5 / 6) return false
        if (y < height / 4 || y > height * 3 / 4) return false

        val pixel = bitmap.getPixel(x, y)
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)

        val isWhiteBg = red > 230 && green > 230 && blue > 230
        val isLightGray = red > 200 && green > 200 && blue > 200

        return isWhiteBg || isLightGray
    }

    private fun applyPrivacyMask(bitmap: Bitmap, regions: List<PrivacyRegion>): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(result)

        val maskPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        for (region in regions) {
            val safeX = region.x.coerceIn(0, result.width - 1)
            val safeY = region.y.coerceIn(0, result.height - 1)
            val safeWidth = region.width.coerceAtMost(result.width - safeX)
            val safeHeight = region.height.coerceAtMost(result.height - safeY)

            if (safeWidth > 0 && safeHeight > 0) {
                canvas.drawRect(
                    safeX.toFloat(), safeY.toFloat(),
                    (safeX + safeWidth).toFloat(), (safeY + safeHeight).toFloat(),
                    maskPaint
                )
                canvas.drawRect(
                    safeX.toFloat(), safeY.toFloat(),
                    (safeX + safeWidth).toFloat(), (safeY + safeHeight).toFloat(),
                    borderPaint
                )
            }
        }

        return result
    }

    private fun mergeOverlappingRegions(regions: MutableList<PrivacyRegion>) {
        if (regions.size <= 1) return

        val merged = mutableListOf<PrivacyRegion>()
        val sorted = regions.sortedBy { it.y }

        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (current.y + current.height >= next.y && current.x + current.width >= next.x) {
                val newX = minOf(current.x, next.x)
                val newY = minOf(current.y, next.y)
                val newW = maxOf(current.x + current.width, next.x + next.width) - newX
                val newH = maxOf(current.y + current.height, next.y + next.height) - newY
                current = PrivacyRegion(newX, newY, newW, newH, current.type)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        regions.clear()
        regions.addAll(merged)
    }

    private fun getBrightness(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    private fun abs(a: Int): Int = if (a < 0) -a else a
}
