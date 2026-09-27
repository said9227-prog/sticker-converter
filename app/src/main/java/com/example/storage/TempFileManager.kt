package com.example.storage

import android.content.Context
import java.io.File
import java.util.UUID

class TempFileManager(private val context: Context) {

    private val tempDir: File
        get() {
            val dir = File(context.cacheDir, "sticker_temp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val stickersDir: File
        get() {
            val dir = File(context.filesDir, "stickers")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun createTempFile(prefix: String = "conv_", suffix: String = ".tmp"): File {
        return File.createTempFile(prefix, suffix, tempDir)
    }

    fun createTempDirectory(prefix: String = "batch_"): File {
        val dir = File(tempDir, "${prefix}${UUID.randomUUID()}")
        dir.mkdirs()
        return dir
    }

    fun getPackDirectory(packId: Long): File {
        val dir = File(stickersDir, "pack_$packId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cleanTempDirectory() {
        try {
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
                tempDir.mkdirs()
            }
        } catch (_: Exception) {
            // Ignore temporary deletion failure
        }
    }

    fun deletePackFiles(packId: Long) {
        try {
            val dir = File(stickersDir, "pack_$packId")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }
}
