package com.picme.data.repository

import android.content.Context
import com.picme.core.common.DuplicateImageDetector
import com.picme.data.local.MediaDao
import com.picme.data.model.MediaEntity
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.File

class MediaRepositoryImpl(
    private val mediaDao: MediaDao,
    private val context: Context
) : MediaRepository {

    override val allMedia: Flow<List<MediaAsset>> = mediaDao.getAllMedia().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun insertMedia(mediaAsset: MediaAsset): Long {
        return mediaDao.insertMedia(mediaAsset.toEntity())
    }

    override suspend fun deleteMedia(mediaAsset: MediaAsset) {
        mediaDao.deleteMedia(mediaAsset.toEntity())
    }

    override suspend fun deleteMediaByIds(ids: List<Long>) {
        mediaDao.deleteMediaByIds(ids)
    }

    override suspend fun getMediaById(id: Long): MediaAsset? {
        return mediaDao.getMediaById(id)?.toDomain()
    }

    override suspend fun findDuplicateMedia(): List<Any> {
        val allAssets = allMedia.firstOrNull() ?: return emptyList()
        
        // 将 URI 转换为 File 对象
        val files = allAssets.mapNotNull { asset ->
            try {
                val file = File(asset.uri.removePrefix("file://"))
                if (file.exists()) file else null
            } catch (e: Exception) {
                null
            }
        }
        
        // 使用 DuplicateImageDetector 查找重复图片
        return DuplicateImageDetector.findDuplicates(files)
    }

    private fun MediaEntity.toDomain(): MediaAsset = MediaAsset(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source
    )

    private fun MediaAsset.toEntity(): MediaEntity = MediaEntity(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source
    )
}
