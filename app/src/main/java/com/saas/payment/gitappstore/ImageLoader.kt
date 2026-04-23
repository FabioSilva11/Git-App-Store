package com.saas.payment.gitappstore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.DrawableRes
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

object ImageLoader {
    private val cache =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount / 1024
        }

    fun load(
        imageView: ImageView,
        url: String?,
        executor: ExecutorService,
        @DrawableRes placeholder: Int = android.R.color.transparent,
    ) {
        imageView.tag = url
        imageView.setImageResource(placeholder)
        if (url.isNullOrBlank()) return

        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = runCatching { download(url) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            imageView.post {
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun download(url: String): Bitmap {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GitAppStore-Android")
        }
        return try {
            val bytes = connection.inputStream.use { it.readBytes() }
            bytes.decodeBitmap()
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.decodeBitmap(): Bitmap {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(this, 0, size, bounds)

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_BITMAP_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_BITMAP_DIMENSION
        ) {
            sampleSize *= 2
        }

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        return BitmapFactory.decodeByteArray(this, 0, size, options)
            ?: error("Nao foi possivel decodificar a imagem")
    }

    private const val MAX_BITMAP_DIMENSION = 1440
}
