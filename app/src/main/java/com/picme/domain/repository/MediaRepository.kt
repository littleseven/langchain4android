package com.picme.domain.repository

import com.picme.domain.model.MediaAsset
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    val allMedia: Flow<List<MediaAsset>>

    suspend fun insertMedia(mediaAsset: MediaAsset): Long

    suspend fun deleteMedia(mediaAsset: MediaAsset)

    suspend fun deleteMediaByIds(ids: List<Long>)

    suspend fun getMediaById(id: Long): MediaAsset?

    suspend fun refreshMediaLibrary()
}
