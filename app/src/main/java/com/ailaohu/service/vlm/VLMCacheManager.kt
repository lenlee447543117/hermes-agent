package com.ailaohu.service.vlm

import android.util.LruCache
import java.security.MessageDigest

class VLMCacheManager {

    private val maxCacheSize = 100 // 增加缓存大小
    private val coordinateCache = LruCache<String, ScreenCoordinate>(maxCacheSize)
    private val pageStateCache = LruCache<String, String>(maxCacheSize)

    fun getCachedCoordinate(base64Image: String, targetDescription: String): ScreenCoordinate? {
        val key = generateKey(base64Image, targetDescription)
        return coordinateCache.get(key)
    }

    fun cacheCoordinate(base64Image: String, targetDescription: String, coordinate: ScreenCoordinate) {
        val key = generateKey(base64Image, targetDescription)
        coordinateCache.put(key, coordinate)
    }

    fun getCachedPageState(base64Image: String): String? {
        val key = generateKey(base64Image, "page_state")
        return pageStateCache.get(key)
    }

    fun cachePageState(base64Image: String, pageState: String) {
        val key = generateKey(base64Image, "page_state")
        pageStateCache.put(key, pageState)
    }

    fun clearCache() {
        coordinateCache.evictAll()
        pageStateCache.evictAll()
    }

    private fun generateKey(vararg parts: String): String {
        val combined = parts.joinToString("|")
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(combined.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class ScreenCoordinate(
    val x: Float,
    val y: Float,
    val confidence: Float = 1.0f
)
