package com.example.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.conversion.BatchConversionProgress
import com.example.conversion.StickerConversionEngine
import com.example.core.model.ConversionStatus
import com.example.core.model.StickerItem
import com.example.core.model.StickerPack
import com.example.core.model.StickerType
import com.example.core.model.ValidationResult
import com.example.data.db.AppDatabase
import com.example.data.repository.PackRepository
import com.example.data.repository.StickerRepository
import com.example.media.MediaDetector
import com.example.storage.SafeZipExtractor
import com.example.storage.TempFileManager
import com.example.telegram.TelegramStickerValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class DetectedImportItem(
    val uriString: String,
    val name: String,
    val type: StickerType,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L
)

class StickerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val packRepository = PackRepository(db.stickerDao())
    private val stickerRepository = StickerRepository(db.stickerDao())
    private val tempFileManager = TempFileManager(application)
    private val conversionEngine = StickerConversionEngine(application, tempFileManager, stickerRepository)
    private val safeZipExtractor = SafeZipExtractor()

    val packs: StateFlow<List<StickerPack>> = packRepository.getAllPacks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentPack = MutableStateFlow<StickerPack?>(null)
    val currentPack: StateFlow<StickerPack?> = _currentPack.asStateFlow()

    private val _currentStickers = MutableStateFlow<List<StickerItem>>(emptyList())
    val currentStickers: StateFlow<List<StickerItem>> = _currentStickers.asStateFlow()

    private val _conversionProgress = MutableStateFlow<BatchConversionProgress?>(null)
    val conversionProgress: StateFlow<BatchConversionProgress?> = _conversionProgress.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importDetectedItems = MutableStateFlow<List<DetectedImportItem>>(emptyList())
    val importDetectedItems: StateFlow<List<DetectedImportItem>> = _importDetectedItems.asStateFlow()

    private var conversionJob: Job? = null

    init {
        // Clean temporary files from previous runs
        viewModelScope.launch {
            tempFileManager.cleanTempDirectory()
        }
    }

    fun selectPack(packId: Long) {
        viewModelScope.launch {
            _currentPack.value = packRepository.getPackById(packId)
            stickerRepository.getStickersByPackIdFlow(packId).collect { list ->
                _currentStickers.value = list
            }
        }
    }

    fun createPack(
        name: String,
        shortName: String,
        author: String,
        defaultEmoji: String,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val pack = StickerPack(
                name = name.ifBlank { "حزمة ملصقات جديدة" },
                shortName = shortName.ifBlank { "pack_" + System.currentTimeMillis() },
                author = author,
                defaultEmoji = defaultEmoji.ifBlank { "✨" }
            )
            val newId = packRepository.createPack(pack)
            selectPack(newId)
            onCreated(newId)
        }
    }

    fun updatePack(pack: StickerPack) {
        viewModelScope.launch {
            packRepository.updatePack(pack)
            if (_currentPack.value?.id == pack.id) {
                _currentPack.value = pack
            }
        }
    }

    fun deletePack(packId: Long) {
        viewModelScope.launch {
            packRepository.deletePack(packId)
            tempFileManager.deletePackFiles(packId)
            if (_currentPack.value?.id == packId) {
                _currentPack.value = null
                _currentStickers.value = emptyList()
            }
        }
    }

    fun inspectUris(uris: List<Uri>) {
        viewModelScope.launch {
            _isImporting.value = true
            val items = mutableListOf<DetectedImportItem>()
            val context = getApplication<Application>()

            for (uri in uris) {
                try {
                    val temp = tempFileManager.createTempFile("inspect_", ".tmp")
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        FileOutputStream(temp).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }

                    val detected = MediaDetector.detect(temp)
                    val rawName = uri.lastPathSegment ?: "sticker"
                    val cleanName = File(rawName).nameWithoutExtension

                    items.add(
                        DetectedImportItem(
                            uriString = uri.toString(),
                            name = cleanName,
                            type = detected.type,
                            sizeBytes = temp.length(),
                            width = detected.width,
                            height = detected.height,
                            durationMs = detected.durationMs
                        )
                    )
                    temp.delete()
                } catch (_: Exception) {
                }
            }
            _importDetectedItems.value = items
            _isImporting.value = false
        }
    }

    fun commitImportToPack(packId: Long, items: List<DetectedImportItem>, onComplete: () -> Unit) {
        viewModelScope.launch {
            val pack = packRepository.getPackById(packId)
            val defaultEmoji = pack?.defaultEmoji ?: "✨"
            val existing = stickerRepository.getStickersByPackId(packId)
            var currentOrder = existing.size

            val newStickers = items.map { item ->
                currentOrder++
                StickerItem(
                    packId = packId,
                    originalUri = item.uriString,
                    originalName = item.name,
                    displayName = item.name,
                    emoji = defaultEmoji,
                    type = item.type,
                    originalSize = item.sizeBytes,
                    width = item.width,
                    height = item.height,
                    durationMs = item.durationMs,
                    sortOrder = currentOrder
                )
            }

            stickerRepository.addStickers(newStickers)
            _importDetectedItems.value = emptyList()
            selectPack(packId)
            onComplete()
        }
    }

    fun importZipArchive(
        zipUri: Uri,
        packNameOverride: String?,
        authorOverride: String?,
        onComplete: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            val context = getApplication<Application>()
            val extractDir = tempFileManager.createTempDirectory("zip_import_")

            try {
                val inStream = context.contentResolver.openInputStream(zipUri)
                if (inStream == null) {
                    onError("تعذر فتح ملف الأرشيف")
                    _isImporting.value = false
                    return@launch
                }

                val result = safeZipExtractor.extract(inStream, extractDir)
                if (result.extractedFiles.isEmpty()) {
                    onError("لم يتم العثور على ملصقات صالحة في الملف")
                    _isImporting.value = false
                    return@launch
                }

                // Determine pack details
                val finalPackName = packNameOverride?.ifBlank { null }
                    ?: result.packTitle
                    ?: "حزمة ملصقات ${System.currentTimeMillis() % 1000}"

                val finalAuthor = authorOverride?.ifBlank { null }
                    ?: result.packAuthor
                    ?: ""

                val shortName = "pack_" + (finalPackName.replace("[^\\w]".toRegex(), "_").lowercase())

                val pack = StickerPack(
                    name = finalPackName,
                    shortName = shortName,
                    author = finalAuthor,
                    defaultEmoji = "✨"
                )

                val packId = packRepository.createPack(pack)

                val stickersToInsert = result.extractedFiles.mapIndexed { index, extracted ->
                    val detected = MediaDetector.detect(extracted.file)
                    StickerItem(
                        packId = packId,
                        originalUri = extracted.file.absolutePath,
                        originalName = extracted.cleanDisplayName,
                        displayName = extracted.cleanDisplayName,
                        emoji = "✨",
                        type = detected.type,
                        originalSize = extracted.file.length(),
                        width = detected.width,
                        height = detected.height,
                        durationMs = detected.durationMs,
                        sortOrder = index
                    )
                }

                stickerRepository.addStickers(stickersToInsert)
                selectPack(packId)
                _isImporting.value = false
                onComplete(packId)
            } catch (e: Exception) {
                e.printStackTrace()
                onError(e.message ?: "فشل استخراج الأرشيف")
                _isImporting.value = false
            }
        }
    }

    fun updateStickerName(stickerId: Long, newName: String) {
        viewModelScope.launch {
            val item = stickerRepository.getStickerById(stickerId) ?: return@launch
            stickerRepository.updateSticker(item.copy(displayName = newName))
        }
    }

    fun updateStickerEmoji(stickerId: Long, newEmoji: String) {
        viewModelScope.launch {
            val item = stickerRepository.getStickerById(stickerId) ?: return@launch
            stickerRepository.updateSticker(item.copy(emoji = newEmoji))
        }
    }

    fun generateNamesAutomatically(prefix: String = "ملصق") {
        viewModelScope.launch {
            val current = _currentStickers.value
            val updated = current.mapIndexed { index, item ->
                val autoName = String.format(java.util.Locale.US, "%s %03d", prefix, index + 1)
                item.copy(displayName = autoName)
            }
            stickerRepository.updateStickers(updated)
        }
    }

    fun deleteSticker(stickerId: Long) {
        viewModelScope.launch {
            stickerRepository.deleteSticker(stickerId)
        }
    }

    fun moveStickerUp(stickerId: Long) {
        val list = _currentStickers.value.toMutableList()
        val index = list.indexOfFirst { it.id == stickerId }
        if (index > 0) {
            val item = list.removeAt(index)
            list.add(index - 1, item)
            viewModelScope.launch {
                stickerRepository.reorderStickers(list.map { it.id })
            }
        }
    }

    fun moveStickerDown(stickerId: Long) {
        val list = _currentStickers.value.toMutableList()
        val index = list.indexOfFirst { it.id == stickerId }
        if (index >= 0 && index < list.size - 1) {
            val item = list.removeAt(index)
            list.add(index + 1, item)
            viewModelScope.launch {
                stickerRepository.reorderStickers(list.map { it.id })
            }
        }
    }

    fun startConversion(stickers: List<StickerItem>) {
        if (conversionJob?.isActive == true) return

        conversionJob = viewModelScope.launch {
            conversionEngine.convertBatch(stickers) { progress ->
                _conversionProgress.value = progress
            }
        }
    }

    fun reprocessSingleSticker(stickerId: Long) {
        viewModelScope.launch {
            val item = stickerRepository.getStickerById(stickerId) ?: return@launch
            stickerRepository.updateSticker(item.copy(conversionStatus = ConversionStatus.PROCESSING))
            conversionEngine.convertSticker(item)
        }
    }

    fun cancelConversion() {
        conversionJob?.cancel()
        _conversionProgress.value = null
    }

    fun dismissConversionDialog() {
        _conversionProgress.value = null
    }

    fun validateSticker(sticker: StickerItem): ValidationResult {
        val path = sticker.outputPath
        return if (path != null && File(path).exists()) {
            TelegramStickerValidator.validate(File(path), sticker.type.isAnimated)
        } else {
            ValidationResult(isValid = false, errors = listOf("لم يتم التحويل بعد"))
        }
    }
}
