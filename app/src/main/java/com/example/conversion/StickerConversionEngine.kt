package com.example.conversion

import android.content.Context
import android.net.Uri
import com.example.core.model.ConversionStatus
import com.example.core.model.StickerItem
import com.example.data.repository.StickerRepository
import com.example.storage.TempFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

data class BatchConversionProgress(
    val current: Int,
    val total: Int,
    val successCount: Int,
    val failedCount: Int,
    val currentStickerName: String,
    val isFinished: Boolean = false
)

class StickerConversionEngine(
    private val context: Context,
    private val tempFileManager: TempFileManager,
    private val stickerRepository: StickerRepository
) {
    private val compressionEngine = CompressionEngine()

    suspend fun convertSticker(sticker: StickerItem): StickerItem = withContext(Dispatchers.IO) {
        val packDir = tempFileManager.getPackDirectory(sticker.packId)
        val isAnimated = sticker.type.isAnimated
        val extension = if (isAnimated) "webm" else "webp"
        val outFile = File(packDir, "sticker_${sticker.id}.$extension")

        // Prepare input file
        val tempInputFile = tempFileManager.createTempFile("in_${sticker.id}_", ".tmp")
        try {
            val originalUri = Uri.parse(sticker.originalUri)
            context.contentResolver.openInputStream(originalUri)?.use { input ->
                FileOutputStream(tempInputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                // If originalUri is a direct file path
                val srcFile = File(sticker.originalUri)
                if (srcFile.exists()) {
                    srcFile.copyTo(tempInputFile, overwrite = true)
                }
            }

            if (!tempInputFile.exists() || tempInputFile.length() == 0L) {
                return@withContext sticker.copy(
                    conversionStatus = ConversionStatus.FAILED,
                    errorMessage = "تعذر قراءة ملف الملصق الأصلي"
                )
            }

            val result = compressionEngine.compressAndConvert(tempInputFile, outFile)

            if (result.success && outFile.exists() && outFile.length() > 0) {
                val updated = sticker.copy(
                    outputUri = Uri.fromFile(outFile).toString(),
                    outputPath = outFile.absolutePath,
                    convertedSize = outFile.length(),
                    width = result.validationResult.width,
                    height = result.validationResult.height,
                    durationMs = result.validationResult.durationMs,
                    conversionStatus = ConversionStatus.SUCCESS,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                stickerRepository.updateSticker(updated)
                return@withContext updated
            } else {
                val errorReason = if (result.validationResult.errors.isNotEmpty()) {
                    result.validationResult.errors.joinToString("، ")
                } else {
                    "فشل تشفير الملف بمواصفات تيليجرام"
                }
                val updated = sticker.copy(
                    conversionStatus = ConversionStatus.FAILED,
                    errorMessage = errorReason,
                    updatedAt = System.currentTimeMillis()
                )
                stickerRepository.updateSticker(updated)
                return@withContext updated
            }
        } catch (e: Exception) {
            val updated = sticker.copy(
                conversionStatus = ConversionStatus.FAILED,
                errorMessage = e.message ?: "خطأ غير متوقع أثناء التحويل",
                updatedAt = System.currentTimeMillis()
            )
            stickerRepository.updateSticker(updated)
            return@withContext updated
        } finally {
            try { tempInputFile.delete() } catch (_: Exception) {}
        }
    }

    suspend fun convertBatch(
        stickers: List<StickerItem>,
        onProgress: (BatchConversionProgress) -> Unit
    ): List<StickerItem> = withContext(Dispatchers.IO) {
        var successCount = 0
        var failedCount = 0
        val results = mutableListOf<StickerItem>()

        for ((index, item) in stickers.withIndex()) {
            coroutineContext.ensureActive()

            onProgress(
                BatchConversionProgress(
                    current = index + 1,
                    total = stickers.size,
                    successCount = successCount,
                    failedCount = failedCount,
                    currentStickerName = item.displayName
                )
            )

            val converted = convertSticker(item)
            if (converted.conversionStatus == ConversionStatus.SUCCESS) {
                successCount++
            } else {
                failedCount++
            }
            results.add(converted)
        }

        onProgress(
            BatchConversionProgress(
                current = stickers.size,
                total = stickers.size,
                successCount = successCount,
                failedCount = failedCount,
                currentStickerName = "",
                isFinished = true
            )
        )

        results
    }
}
