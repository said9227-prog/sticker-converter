package com.example.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.core.model.ConversionStatus
import com.example.core.model.StickerItem
import com.example.core.model.StickerPack
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TelegramExporter {

    private fun getFileProviderAuthority(context: Context): String {
        return "${context.packageName}.provider"
    }

    /**
     * Official Telegram Sticker Import Intent:
     * action = "org.telegram.messenger.CREATE_STICKER_PACK"
     * EXTRA_STREAM = ArrayList<Uri>
     * STICKER_EMOJIS = ArrayList<String>
     */
    fun createTelegramImportIntent(
        context: Context,
        pack: StickerPack,
        stickers: List<StickerItem>
    ): Intent? {
        val readyStickers = stickers.filter { it.conversionStatus == ConversionStatus.SUCCESS && it.outputPath != null }
        if (readyStickers.isEmpty()) return null

        val uriList = ArrayList<Uri>()
        val emojiList = ArrayList<String>()
        val authority = getFileProviderAuthority(context)

        for (item in readyStickers) {
            val file = File(item.outputPath!!)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, authority, file)
                uriList.add(uri)
                emojiList.add(item.emoji.ifBlank { pack.defaultEmoji })
            }
        }

        if (uriList.isEmpty()) return null

        val intent = Intent("org.telegram.messenger.CREATE_STICKER_PACK").apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            putStringArrayListExtra("STICKER_EMOJIS", emojiList)
            putExtra("IMPORTER", context.packageName)
            val hasVideo = readyStickers.any { it.type.isAnimated }
            type = if (hasVideo) "video/webm" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return intent
    }

    /**
     * Builds a clean ZIP archive with the final Telegram sticker files and metadata json.
     */
    fun buildTelegramStickersZip(
        context: Context,
        pack: StickerPack,
        stickers: List<StickerItem>,
        destinationZipFile: File
    ): Boolean {
        val readyStickers = stickers.filter { it.conversionStatus == ConversionStatus.SUCCESS && it.outputPath != null }
        if (readyStickers.isEmpty()) return false

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destinationZipFile))).use { zipOut ->
                // Write each converted sticker
                readyStickers.forEachIndexed { index, item ->
                    val file = File(item.outputPath!!)
                    if (file.exists()) {
                        val extension = file.extension
                        val sanitizedName = item.displayName.replace("[^\\w\\p{L}-]".toRegex(), "_")
                        val entryName = String.format(java.util.Locale.US, "%03d_%s.%s", index + 1, sanitizedName, extension)
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }

                // Write metadata file
                val metadataJson = """
                {
                  "title": "${pack.name}",
                  "short_name": "${pack.shortName}",
                  "author": "${pack.author}",
                  "stickers_count": ${readyStickers.size},
                  "stickers": [
                    ${
                    readyStickers.mapIndexed { index, item ->
                        """{"index": ${index + 1}, "name": "${item.displayName}", "emoji": "${item.emoji}"}"""
                    }.joinToString(",\n    ")
                }
                  ]
                }
                """.trimIndent()

                zipOut.putNextEntry(ZipEntry("pack_metadata.json"))
                zipOut.write(metadataJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Prepares standard Android Sharesheet Intent for sharing the ZIP file.
     */
    fun createShareZipIntent(context: Context, zipFile: File): Intent {
        val authority = getFileProviderAuthority(context)
        val uri = FileProvider.getUriForFile(context, authority, zipFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
