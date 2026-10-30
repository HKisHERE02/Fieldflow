package com.harshkatariya.fieldflow.data

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PhotoEvidenceException(message: String) : IllegalArgumentException(message)

/** One bounded file/content check shared by submission and upload. Never called inside a Room transaction. */
object PhotoEvidence {
    const val MAX_BYTES = 5 * 1024 * 1024
    suspend fun read(photo: AttachmentRow): ByteArray = withContext(Dispatchers.IO) {
        fun reject(reason: String): Nothing = throw PhotoEvidenceException("Photo ${photo.id}: $reason. Restore the original readable JPEG (at most 5 MB), then retry. Your answers remain saved.")
        val file = File(photo.path)
        val bytes = try {
            if (!file.isFile || !file.canRead()) reject("file is missing or unreadable")
            if (file.length() > MAX_BYTES) reject("exceeds the 5 MB limit")
            // Bound the read as well as the initial length check in case the file changes during validation.
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_BYTES) reject("exceeds the 5 MB limit")
                }
                output.toByteArray()
            }
        } catch (_: IOException) { reject("file could not be read") }
          catch (_: SecurityException) { reject("file access was denied") }
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
            bytes[2] != 0xff.toByte() || bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) reject("not a complete JPEG")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth <= 0 || bounds.outHeight <= 0) reject("invalid JPEG content")
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: reject("JPEG cannot be decoded")
        decoded.recycle()
        bytes
    }
}
