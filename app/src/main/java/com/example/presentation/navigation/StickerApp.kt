package com.example.presentation.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.presentation.StickerViewModel
import com.example.presentation.screens.AboutScreen
import com.example.presentation.screens.ImportScreen
import com.example.presentation.screens.PackDetailScreen
import com.example.presentation.screens.PackListScreen

sealed class Screen {
    data object PackList : Screen()
    data class Import(val packId: Long? = null) : Screen()
    data class PackDetail(val packId: Long) : Screen()
    data object About : Screen()
}

@Composable
fun StickerApp(
    viewModel: StickerViewModel = viewModel()
) {
    val packs by viewModel.packs.collectAsState()
    val currentPack by viewModel.currentPack.collectAsState()
    val currentStickers by viewModel.currentStickers.collectAsState()
    val conversionProgress by viewModel.conversionProgress.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val detectedItems by viewModel.importDetectedItems.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.PackList) }

    // Enforce Arabic RTL layout as requested
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (val screen = currentScreen) {
                is Screen.PackList -> {
                    PackListScreen(
                        packs = packs,
                        onOpenPack = { packId ->
                            viewModel.selectPack(packId)
                            currentScreen = Screen.PackDetail(packId)
                        },
                        onCreatePack = { name, shortName, author, defaultEmoji ->
                            viewModel.createPack(name, shortName, author, defaultEmoji) { newId ->
                                currentScreen = Screen.PackDetail(newId)
                            }
                        },
                        onDeletePack = { packId ->
                            viewModel.deletePack(packId)
                        },
                        onNavigateToImport = {
                            currentScreen = Screen.Import()
                        },
                        onNavigateToAbout = {
                            currentScreen = Screen.About
                        }
                    )
                }

                is Screen.Import -> {
                    BackHandler {
                        currentScreen = if (screen.packId != null) {
                            Screen.PackDetail(screen.packId)
                        } else {
                            Screen.PackList
                        }
                    }

                    ImportScreen(
                        packs = packs,
                        initialPackId = screen.packId,
                        isImporting = isImporting,
                        detectedItems = detectedItems,
                        onInspectUris = { uris ->
                            viewModel.inspectUris(uris)
                        },
                        onImportZip = { uri, onComplete, onError ->
                            viewModel.importZipArchive(uri, null, null, onComplete, onError)
                        },
                        onCommitImport = { packId, items, onComplete ->
                            viewModel.commitImportToPack(packId, items) {
                                onComplete()
                            }
                        },
                        onCreatePackAndImport = { name, items, onComplete ->
                            viewModel.createPack(name, "pack_" + System.currentTimeMillis() % 10000, "", "✨") { newPackId ->
                                viewModel.commitImportToPack(newPackId, items) {
                                    onComplete(newPackId)
                                }
                            }
                        },
                        onNavigateBack = {
                            currentScreen = if (screen.packId != null) {
                                Screen.PackDetail(screen.packId)
                            } else {
                                Screen.PackList
                            }
                        },
                        onOpenPack = { packId ->
                            viewModel.selectPack(packId)
                            currentScreen = Screen.PackDetail(packId)
                        }
                    )
                }

                is Screen.PackDetail -> {
                    BackHandler {
                        currentScreen = Screen.PackList
                    }

                    if (currentPack != null && currentPack!!.id == screen.packId) {
                        PackDetailScreen(
                            pack = currentPack!!,
                            stickers = currentStickers,
                            conversionProgress = conversionProgress,
                            onNavigateBack = { currentScreen = Screen.PackList },
                            onNavigateToImport = { packId -> currentScreen = Screen.Import(packId) },
                            onUpdateStickerName = { id, name -> viewModel.updateStickerName(id, name) },
                            onUpdateStickerEmoji = { id, emoji -> viewModel.updateStickerEmoji(id, emoji) },
                            onGenerateNamesAutomatically = { prefix -> viewModel.generateNamesAutomatically(prefix) },
                            onDeleteSticker = { id -> viewModel.deleteSticker(id) },
                            onMoveStickerUp = { id -> viewModel.moveStickerUp(id) },
                            onMoveStickerDown = { id -> viewModel.moveStickerDown(id) },
                            onStartConversion = { list -> viewModel.startConversion(list) },
                            onReprocessSticker = { id -> viewModel.reprocessSingleSticker(id) },
                            onCancelConversion = { viewModel.cancelConversion() },
                            onDismissConversionProgress = { viewModel.dismissConversionDialog() },
                            onValidateSticker = { item -> viewModel.validateSticker(item) }
                        )
                    } else {
                        // Reload pack if needed
                        viewModel.selectPack(screen.packId)
                    }
                }

                is Screen.About -> {
                    BackHandler {
                        currentScreen = Screen.PackList
                    }

                    AboutScreen(
                        onNavigateBack = { currentScreen = Screen.PackList }
                    )
                }
            }
        }
    }
}
