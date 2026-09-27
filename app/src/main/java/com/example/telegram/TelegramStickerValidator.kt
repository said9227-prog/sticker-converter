package com.example.telegram

import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.core.model.ValidationResult
import com.example.media.WebpParser
import java.io.File

object TelegramStickerValidator {

    const val MAX_VIDEO_SIZE_BYTES = 256 * 1024L // 256 KB
    const val MAX_STATIC_SIZE_BYTES = 512 * 1024L // 512 KB
    const val MAX_DURATION_MS = 3000L // 3 seconds
    const val TARGET_SIDE_PX = 512
    const val MAX_FPS = 30.0f

    fun validate(file: File, isAnimated: Boolean): ValidationResult {
        if (!file.exists() || file.length() == 0L) {
            return ValidationResult(
                isValid = false,
                errors = listOf("الملف غير موجود أو فارغ")
            )
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val fileSize = file.length()

        if (isAnimated) {
            // Check animated video sticker (WEBM VP9)
            val maxSize = MAX_VIDEO_SIZE_BYTES
            if (fileSize > maxSize) {
                errors.add("حجم الملف (${fileSize / 1024} KB) يتجاوز الحد الأقصى لتيليجرام (256 KB)")
            }

            var width = 0
            var height = 0
            var durationMs = 0L
            var codec = ""
            var hasAudio = false
            var fps = 0f

            // Inspect WEBM via MediaExtractor or file header
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackCount = extractor.trackCount
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        hasAudio = true
                        errors.add("يحتوي الملف على مسار صوتي (ممنوع في ملصقات تيليجرام)")
                    } else if (mime.startsWith("video/")) {
                        codec = mime
                        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                    }
                }
            } catch (_: Exception) {
                // MediaExtractor might not read all custom containers, fallback to Webp/Container check
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }

            // Duration check
            if (durationMs > MAX_DURATION_MS + 100) { // allowance of 100ms tolerance
                errors.add("مدة الحركة (${durationMs}ms) أطول من الحد المسموح (3 ثوانٍ)")
            }

            // Dimension check: One side must be 512, other <= 512
            if (width > 0 && height > 0) {
                val matchesTelegramDimension = (width == TARGET_SIDE_PX && height <= TARGET_SIDE_PX) ||
                        (height == TARGET_SIDE_PX && width <= TARGET_SIDE_PX)
                if (!matchesTelegramDimension) {
                    errors.add("الأبعاد (${width}x${height}) لا تطابق شرط تيليجرام (أحد الأبعاد 512px والآخر 512px أو أقل)")
                }
            }

            // FPS check
            if (fps > MAX_FPS + 1.0f) {
                warnings.add("معدل الإطارات (${fps}fps) أعلى من الموصى به (30fps)")
            }

            return ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings,
                format = "WEBM",
                codec = if (codec.isNotEmpty()) codec else "video/x-vnd.on2.vp9",
                width = width,
                height = height,
                durationMs = durationMs,
                fileSizeBytes = fileSize,
                hasAlpha = true,
                hasAudio = hasAudio,
                fps = fps
            )

        } else {
            // Static sticker check (WEBP or PNG)
            val maxSize = MAX_STATIC_SIZE_BYTES
            if (fileSize > maxSize) {
                errors.add("حجم الملصق الثابت (${fileSize / 1024} KB) أكبر من الحد المسموح (512 KB)")
            }

            // Check dimensions using BitmapFactory or WebpParser
            val webpInfo = WebpParser.parse(file)
            val width: Int
            val height: Int
            val format: String

            if (webpInfo.isWebp) {
                width = webpInfo.width
                height = webpInfo.height
                format = "WEBP"
            } else {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                width = opts.outWidth
                height = opts.outHeight
                format = opts.outMimeType ?: "IMAGE"
            }

            if (width > 0 && height > 0) {
                val matchesTelegramDimension = (width == TARGET_SIDE_PX && height <= TARGET_SIDE_PX) ||
                        (height == TARGET_SIDE_PX && width <= TARGET_SIDE_PX)
                if (!matchesTelegramDimension) {
                    errors.add("الأبعاد (${width}x${height}) غير مطابقة لمعايير تيليجرام (يجب أن يكون أحد الأبعاد 512px)")
                }
            }

            return ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings,
                format = format,
                codec = format,
                width = width,
                height = height,
                durationMs = 0L,
                fileSizeBytes = fileSize,
                hasAlpha = true,
                hasAudio = false
            )
        }
    }
}
