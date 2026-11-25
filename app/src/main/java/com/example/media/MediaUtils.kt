package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object MediaUtils {
    private val imageCache = LruCache<Int, ImageBitmap>(48)

    fun decodeBase64ToImageBitmap(base64: String?): ImageBitmap? {
        if (base64.isNullOrBlank()) return null
        val key = base64.hashCode()
        imageCache.get(key)?.let { return it }
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val img = bmp.asImageBitmap()
            imageCache.put(key, img)
            img
        } catch (_: Exception) {
            null
        }
    }

    suspend fun compressUriToBase64(
        context: Context,
        uri: Uri,
        maxDimension: Int = 720,
        maxBase64Chars: Int = 220_000
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sampleSize = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sampleSize > maxDimension * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@withContext null

            compressBitmapInternal(rawBitmap, maxDimension, maxBase64Chars)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun compressBitmapToBase64(
        bitmap: Bitmap,
        maxDimension: Int = 720,
        maxBase64Chars: Int = 220_000
    ): String? = withContext(Dispatchers.IO) {
        try {
            compressBitmapInternal(bitmap, maxDimension, maxBase64Chars)
        } catch (_: Exception) {
            null
        }
    }

    private fun compressBitmapInternal(
        source: Bitmap,
        maxDimension: Int,
        maxBase64Chars: Int
    ): String? {
        val ratio = maxDimension.toFloat() / max(source.width, source.height).coerceAtLeast(1)
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        var quality = 82
        val baos = ByteArrayOutputStream()
        var encoded: String
        do {
            baos.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            quality -= 12
        } while (encoded.length > maxBase64Chars && quality >= 24)

        return if (encoded.length <= maxBase64Chars) encoded else null
    }

    suspend fun readSmallDocument(
        context: Context,
        uri: Uri,
        maxRawBytes: Int = 250_000
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri) ?: "Attachment"
        val base64 = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size <= maxRawBytes) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
            }
        } catch (_: Exception) {
            null
        }
        name to base64
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun calculateCacheBytes(context: Context): Long {
        fun dirSize(file: File?): Long {
            if (file == null || !file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { dirSize(it) } ?: 0L
        }
        return dirSize(context.cacheDir)
    }

    fun clearAppCache(context: Context): Long {
        imageCache.evictAll()
        context.cacheDir?.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
        return calculateCacheBytes(context)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.2f MB", mb)
    }
}
