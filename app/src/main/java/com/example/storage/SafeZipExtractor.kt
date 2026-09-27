package com.example.storage

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ExtractedPackResult(
    val packTitle: String?,
    val packAuthor: String?,
    val extractedFiles: List<ExtractedFileItem>
)

data class ExtractedFileItem(
    val file: File,
    val originalEntryName: String,
    val cleanDisplayName: String
)

class SafeZipExtractor {

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_TOTAL_SIZE_BYTES = 200L * 1024 * 1024 // 200 MB
        const val MAX_SINGLE_FILE_SIZE = 50L * 1024 * 1024 // 50 MB
    }

    fun extract(inputStream: InputStream, destinationDir: File): ExtractedPackResult {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        val destCanonicalPath = destinationDir.canonicalPath

        var packTitle: String? = null
        var packAuthor: String? = null
        val extractedFiles = mutableListOf<ExtractedFileItem>()

        val zipIn = ZipInputStream(BufferedInputStream(inputStream))
        var totalBytesRead = 0L
        var totalEntries = 0
        val usedFilenames = mutableMapOf<String, Int>()

        var entry: ZipEntry? = zipIn.nextEntry
        while (entry != null) {
            totalEntries++
            if (totalEntries > MAX_ENTRIES) {
                throw SecurityException("تم تجاوز الحد الأقصى المسموح لعدد الملفات في الأرشيف ($MAX_ENTRIES)")
            }

            val entryName = entry.name
            // Zip Slip path traversal security check
            val targetFileCheck = File(destinationDir, entryName)
            val testCanonical = targetFileCheck.canonicalPath
            if (!testCanonical.startsWith(destCanonicalPath + File.separator) && testCanonical != destCanonicalPath) {
                throw SecurityException("محاولة Zip Slip غير آمنة: $entryName")
            }

            // Normalize path
            val cleanName = File(entryName).name
            if (cleanName.isBlank() || cleanName.startsWith(".") || cleanName.startsWith("__MACOSX")) {
                zipIn.closeEntry()
                entry = zipIn.nextEntry
                continue
            }

            // Handle metadata text files
            if (cleanName.equals("title.txt", ignoreCase = true)) {
                packTitle = zipIn.bufferedReader().readLine()?.trim()
                zipIn.closeEntry()
                entry = zipIn.nextEntry
                continue
            }
            if (cleanName.equals("author.txt", ignoreCase = true)) {
                packAuthor = zipIn.bufferedReader().readLine()?.trim()
                zipIn.closeEntry()
                entry = zipIn.nextEntry
                continue
            }

            if (!entry.isDirectory) {
                // Determine unique safe filename for duplicate prevention
                val extension = cleanName.substringAfterLast('.', "")
                val baseName = cleanName.substringBeforeLast('.')
                val count = usedFilenames.getOrDefault(cleanName, 0)
                usedFilenames[cleanName] = count + 1

                val safeDiskName = if (count == 0) {
                    cleanName
                } else {
                    "${baseName}_${String.format(java.util.Locale.US, "%03d", count)}.${extension}"
                }

                val outFile = File(destinationDir, safeDiskName)
                val outCanonical = outFile.canonicalPath

                // Zip Slip protection
                if (!outCanonical.startsWith(destCanonicalPath + File.separator) && outCanonical != destCanonicalPath) {
                    throw SecurityException("محاولة Zip Slip غير آمنة: $entryName")
                }

                // Write file with size limits
                var fileBytes = 0L
                val buffer = ByteArray(8192)
                FileOutputStream(outFile).use { fos ->
                    var len: Int
                    while (zipIn.read(buffer).also { len = it } > 0) {
                        fileBytes += len
                        totalBytesRead += len
                        if (fileBytes > MAX_SINGLE_FILE_SIZE) {
                            throw SecurityException("حجم الملف $cleanName يتجاوز الحد الأقصى المسموح")
                        }
                        if (totalBytesRead > MAX_TOTAL_SIZE_BYTES) {
                            throw SecurityException("حجم الأرشيف الإجمالي يتجاوز الحد الأقصى المسموح")
                        }
                        fos.write(buffer, 0, len)
                    }
                }

                extractedFiles.add(
                    ExtractedFileItem(
                        file = outFile,
                        originalEntryName = entryName,
                        cleanDisplayName = baseName
                    )
                )
            }

            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        return ExtractedPackResult(
            packTitle = packTitle,
            packAuthor = packAuthor,
            extractedFiles = extractedFiles
        )
    }
}
