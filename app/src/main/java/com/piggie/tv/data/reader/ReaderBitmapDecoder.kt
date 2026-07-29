package com.piggie.tv.data.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

object ReaderBitmapDecoder {
    fun decode(openStream: () -> InputStream?, targetWidth: Int, targetHeight: Int): Bitmap? {
        // Decode from one immutable byte array. Reopening a ZipFile entry for the
        // bounds pass and pixel pass is unreliable on some Fire OS builds and was
        // producing a null bitmap for otherwise valid JPEG pages.
        val encoded = openStream()?.use(InputStream::readBytes) ?: return null
        if (encoded.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val fitScale = minOf(
            targetWidth.coerceAtLeast(1).toFloat() / bounds.outWidth,
            targetHeight.coerceAtLeast(1).toFloat() / bounds.outHeight
        ).coerceAtMost(1f)
        val desiredWidth = (bounds.outWidth * fitScale).toInt().coerceAtLeast(1)
        val desiredHeight = (bounds.outHeight * fitScale).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= desiredWidth &&
            bounds.outHeight / (sample * 2) >= desiredHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
    }
}
