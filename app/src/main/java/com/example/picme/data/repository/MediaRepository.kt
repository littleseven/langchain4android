package com.example.picme.data.repository

import com.example.picme.data.local.MediaDao
import com.example.picme.data.model.MediaAsset
import kotlinx.coroutines.flow.Flow

class MediaRepository(private val mediaDao: MediaDao) {
    val allMedia: Flow<List<MediaAsset>> = mediaDao.getAllMedia()

    suspend fun insertMedia(mediaAsset: MediaAsset): Long {
        return mediaDao.insertMedia(mediaAsset)
    }

    suspend fun deleteMedia(mediaAsset: MediaAsset) {
        mediaDao.deleteMedia(mediaAsset)
    }

    suspend fun deleteMediaByIds(ids: List<Long>) {
        mediaDao.deleteMediaByIds(ids)
    }

    suspend fun getMediaById(id: Long): MediaAsset? {
        return mediaDao.getMediaById(id)
    }
}
