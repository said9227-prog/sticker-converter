package com.example.data.repository

import com.example.core.model.StickerItem
import com.example.data.db.StickerDao
import com.example.data.db.StickerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StickerRepository(private val dao: StickerDao) {

    fun getStickersByPackIdFlow(packId: Long): Flow<List<StickerItem>> {
        return dao.getStickersByPackIdFlow(packId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getStickersByPackId(packId: Long): List<StickerItem> {
        return dao.getStickersByPackId(packId).map { it.toDomain() }
    }

    suspend fun getStickerById(id: Long): StickerItem? {
        return dao.getStickerById(id)?.toDomain()
    }

    suspend fun addSticker(sticker: StickerItem): Long {
        return dao.insertSticker(StickerEntity.fromDomain(sticker))
    }

    suspend fun addStickers(stickers: List<StickerItem>): List<Long> {
        return dao.insertStickers(stickers.map { StickerEntity.fromDomain(it) })
    }

    suspend fun updateSticker(sticker: StickerItem) {
        dao.updateSticker(StickerEntity.fromDomain(sticker))
    }

    suspend fun updateStickers(stickers: List<StickerItem>) {
        dao.updateStickers(stickers.map { StickerEntity.fromDomain(it) })
    }

    suspend fun deleteSticker(id: Long) {
        dao.deleteStickerById(id)
    }

    suspend fun reorderStickers(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            dao.updateSortOrder(id, index)
        }
    }
}
