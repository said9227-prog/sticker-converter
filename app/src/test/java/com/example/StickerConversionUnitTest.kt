package com.example

import com.example.conversion.WebmMuxer
import com.example.core.model.StickerType
import com.example.media.BitmapUtils
import com.example.media.MediaDetector
import com.example.media.WebpParser
import com.example.storage.SafeZipExtractor
import com.example.telegram.TelegramStickerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerConversionUnitTest {

    @Test
    fun testWebpParserWithCorruptedData() {
        val corruptedBytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val info = WebpParser.parse(corruptedBytes.inputStream())
        assertFalse("Corrupted bytes should not be detected as valid WebP", info.isWebp)
        assertEquals(0, info.width)
        assertEquals(0, info.height)
    }

    @Test
    fun testMediaDetectorRecognizesPng() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 2, 0, // Width: 512
            0, 0, 2, 0, // Height: 512
            8, 6, 0, 0, 0
        )
        val detected = MediaDetector.detect(pngHeader.inputStream(), pngHeader.size.toLong())
        assertEquals(StickerType.PNG, detected.type)
        assertEquals(512, detected.width)
        assertEquals(512, detected.height)
    }

    @Test
    fun testMediaDetectorRecognizesJpg() {
        val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
        val detected = MediaDetector.detect(jpgHeader.inputStream(), jpgHeader.size.toLong())
        assertEquals(StickerType.JPG, detected.type)
        assertFalse(detected.hasAlpha)
    }

    @Test
    fun testMediaDetectorRecognizesWebm() {
        val webmHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val detected = MediaDetector.detect(webmHeader.inputStream(), webmHeader.size.toLong())
        assertEquals(StickerType.WEBM, detected.type)
    }

    @Test
    fun testTelegramDimensionsPreserveAspectRatioSquare() {
        val dims = BitmapUtils.calculateTelegramDimensions(1000, 1000, forceEven = false)
        assertEquals(512, dims.width)
        assertEquals(512, dims.height)
    }

    @Test
    fun testTelegramDimensionsPreserveAspectRatioWide() {
        val dims = BitmapUtils.calculateTelegramDimensions(800, 400, forceEven = false)
        assertEquals(512, dims.width)
        assertEquals(256, dims.height)
    }

    @Test
    fun testTelegramDimensionsPreserveAspectRatioTall() {
        val dims = BitmapUtils.calculateTelegramDimensions(300, 600, forceEven = false)
        assertEquals(256, dims.width)
        assertEquals(512, dims.height)
    }

    @Test
    fun testTelegramDimensionsForceEven() {
        val dims = BitmapUtils.calculateTelegramDimensions(500, 333, forceEven = true)
        assertEquals(512, dims.width)
        assertTrue("Height must be even for video encoders", dims.height % 2 == 0)
    }

    @Test(expected = SecurityException::class)
    fun testZipSlipVulnerabilityPrevented() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../etc/passwd"))
            zos.write("malicious payload".toByteArray())
            zos.closeEntry()
        }

        val extractor = SafeZipExtractor()
        val tempDir = File.createTempFile("test_dest_", "_dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            extractor.extract(ByteArrayInputStream(baos.toByteArray()), tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDuplicateFilenamesHandledSafely() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("set_a/sticker.webp"))
            zos.write("sticker 1".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("set_b/sticker.webp"))
            zos.write("sticker 2".toByteArray())
            zos.closeEntry()
        }

        val extractor = SafeZipExtractor()
        val tempDir = File.createTempFile("test_dup_", "_dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val result = extractor.extract(ByteArrayInputStream(baos.toByteArray()), tempDir)
            assertEquals(2, result.extractedFiles.size)
            assertFalse(
                "Files must have distinct filenames on disk",
                result.extractedFiles[0].file.name == result.extractedFiles[1].file.name
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testWastickersMetadataExtraction() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("title.txt"))
            zos.write("My WhatsApp Pack\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("author.txt"))
            zos.write("Senior Engineer\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("01_laugh.webp"))
            zos.write("mock webp".toByteArray())
            zos.closeEntry()
        }

        val extractor = SafeZipExtractor()
        val tempDir = File.createTempFile("test_meta_", "_dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val result = extractor.extract(ByteArrayInputStream(baos.toByteArray()), tempDir)
            assertEquals("My WhatsApp Pack", result.packTitle)
            assertEquals("Senior Engineer", result.packAuthor)
            assertEquals(1, result.extractedFiles.size)
            assertEquals("01_laugh", result.extractedFiles[0].cleanDisplayName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTelegramStickerValidatorSizeLimit() {
        val tempFile = File.createTempFile("large_test_", ".webm")
        try {
            // Write 300 KB file (exceeds 256 KB limit)
            val bytes = ByteArray(300 * 1024)
            tempFile.writeBytes(bytes)

            val validation = TelegramStickerValidator.validate(tempFile, isAnimated = true)
            assertFalse("File larger than 256 KB must be rejected", validation.isValid)
            assertTrue(validation.errors.any { it.contains("256 KB") })
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testEbmlWebmMuxerHeaderCreation() {
        val muxer = WebmMuxer()
        val header = muxer.createEbmlHeader()
        assertNotNull(header)
        assertTrue(header.size > 20)
        // Check EBML ID 0x1A45DFA3 in first 4 bytes
        assertEquals(0x1A.toByte(), header[0])
        assertEquals(0x45.toByte(), header[1])
        assertEquals(0xDF.toByte(), header[2])
        assertEquals(0xA3.toByte(), header[3])
    }
}
