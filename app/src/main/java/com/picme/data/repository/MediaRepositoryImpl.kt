package com.picme.data.repository

import android.content.Context
import com.picme.data.local.MediaDao
import com.picme.data.model.MediaEntity
import com.picme.domain.model.MediaAsset
import com.picme.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
