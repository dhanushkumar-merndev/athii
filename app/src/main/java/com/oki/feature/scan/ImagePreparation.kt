package com.oki.feature.scan

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImagePreparation(private val context: Context) {
    suspend fun prepare(uri: Uri): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Image could not be read. Choose another image."
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap =
                resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: error("Image could not be read.")
            try {
                val orientation =
                    resolver.openInputStream(uri).use { stream ->
                        stream?.let {
                            ExifInterface(it)
                                .getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL,
                                )
                        }
                    } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix =
                    Matrix().apply {
                        when (orientation) {
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> {
                                setRotate(90f)
                                postScale(-1f, 1f)
                            }
                            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                            ExifInterface.ORIENTATION_TRANSVERSE -> {
                                setRotate(-90f)
                                postScale(-1f, 1f)
                            }
                            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                        }
                    }
                if (!matrix.isIdentity) {
                    val rotated =
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated !== bitmap) bitmap.recycle()
                    bitmap = rotated
                }
                val scale = 1800f / max(bitmap.width, bitmap.height)
                if (scale < 1f) {
                    val resized =
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    if (resized !== bitmap) bitmap.recycle()
                    bitmap = resized
                }
                val folder = File(context.cacheDir, "scans").apply { mkdirs() }
                folder
                    .listFiles()
                    ?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
                    ?.forEach { it.delete() }
                val file = File(folder, "${UUID.randomUUID()}.jpg")
                file.outputStream().use {
                    require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) {
                        "Could not prepare image."
                    }
                }
                file
            } finally {
                bitmap.recycle()
            }
        }
}
