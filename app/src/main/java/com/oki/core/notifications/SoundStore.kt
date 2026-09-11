package com.oki.core.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SoundStore(private val context: Context) {
    private var preview: Ringtone? = null

    suspend fun import(uri: Uri): String =
        withContext(Dispatchers.IO) {
            require(context.contentResolver.getType(uri)?.startsWith("audio/") == true) {
                "Choose an audio file."
            }
            val folder = File(context.filesDir, "sounds").apply { mkdirs() }
            val copy = File(folder, "${UUID.randomUUID()}.audio")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open the audio file." }
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            total += size
                            require(total <= 5 * 1024 * 1024) { "Audio file is too large." }
                            output.write(buffer, 0, size)
                        }
                    }
                }
                val reader = MediaMetadataRetriever()
                val duration =
                    try {
                        reader.setDataSource(copy.path)
                        reader
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                    } finally {
                        reader.release()
                    }
                require(duration != null && duration in 1..5000) {
                    "Choose a sound no longer than 5 seconds."
                }
                FileProvider.getUriForFile(context, "${context.packageName}.files", copy).toString()
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }

    fun preview(uri: Uri) {
        stop()
        preview =
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes =
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                play()
            }
    }

    fun stop() {
        preview?.stop()
        preview = null
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            stop()
            File(context.filesDir, "sounds").deleteRecursively()
            Unit
        }
}
