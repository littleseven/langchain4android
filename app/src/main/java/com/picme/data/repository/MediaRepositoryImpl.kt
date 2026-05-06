package com.picme.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.picme.data.local.MediaDao
import com.picme.data.model.MediaEntity
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class MediaRepositoryImpl(
    private val mediaDao: MediaDao,
    context: Context
) : MediaRepository {

    private val appContext = context.applicationContext
    private val refreshVersion = MutableStateFlow(0)

    override val allMedia: Flow<List<MediaAsset>> = combine(
        mediaDao.getAllMedia(),
        refreshVersion
    ) { entities, _ ->
        val dbMedia = entities.map { entity -> entity.toDomain() }
        mergeMedia(dbMedia = dbMedia, systemMedia = loadSystemMedia())
    }

    override suspend fun insertMedia(mediaAsset: MediaAsset): Long {
        return mediaDao.insertMedia(mediaAsset.toEntity())
    }

    override suspend fun deleteMedia(mediaAsset: MediaAsset) {
        deleteMediaByIds(listOf(mediaAsset.id))
    }

    override suspend fun deleteMediaByIds(ids: List<Long>) {
        if (ids.isEmpty()) {
            return
        }

        val idSet = ids.toSet()
        Log.d("PicMe:Gallery", "Starting deletion for IDs: $idSet")

        // 1. 获取待删除资产的 URI（优先从内存流，其次查库）
        val assetsToDelete = mutableListOf<MediaAsset>()
        
        // 尝试从当前流中获取
        val currentAssets = allMedia.first()
        assetsToDelete.addAll(currentAssets.filter { asset -> asset.id in idSet })
        
        // 补充查询：如果流中没找到（可能是刚插入的本地记录），直接查 Room
        val missingIds = idSet - assetsToDelete.map { it.id }.toSet()
        if (missingIds.isNotEmpty()) {
            val dbEntities = mediaDao.getMediaByIds(missingIds.toList())
            assetsToDelete.addAll(dbEntities.map { it.toDomain() })
        }

        // 2. 执行物理文件删除
        assetsToDelete.forEach { asset ->
            Log.d("PicMe:Gallery", "Attempting to delete file: ${asset.uri}")
            deleteSystemMedia(asset.uri)
        }

        // 3. 清理 Room 数据库记录
        val localIds = ids.filter { id -> id > 0L }
        if (localIds.isNotEmpty()) {
            Log.d("PicMe:Gallery", "Deleting local DB records: $localIds")
            mediaDao.deleteMediaByIds(localIds)
        }

        // 4. 强制刷新媒体库以确保 UI 同步
        refreshMediaLibrary()
        Log.d("PicMe:Gallery", "Deletion completed and library refreshed.")
    }

    override suspend fun getMediaById(id: Long): MediaAsset? {
        if (id > 0L) {
            return mediaDao.getMediaById(id)?.toDomain() ?: allMedia.first().firstOrNull { asset -> asset.id == id }
        }
        return allMedia.first().firstOrNull { asset -> asset.id == id }
    }

    override suspend fun refreshMediaLibrary() {
        refreshVersion.value = refreshVersion.value + 1
    }

    private suspend fun loadSystemMedia(): List<MediaAsset> = withContext(Dispatchers.IO) {
        val media = mutableListOf<MediaAsset>()
        if (hasImageReadPermission()) {
            media += queryImagesFromMediaStore()
        }
        if (hasVideoReadPermission()) {
            media += queryVideosFromMediaStore()
        }
        media.sortedByDescending { asset -> asset.captureDate }
    }

    private fun queryImagesFromMediaStore(): List<MediaAsset> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext()) {
                        val mediaStoreId = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId
                        )
                        add(
                            MediaAsset(
                                id = syntheticMediaId(mediaStoreId, MediaType.PHOTO),
                                uri = uri.toString(),
                                type = MediaType.PHOTO,
                                captureDate = resolveCaptureDate(
                                    dateTakenMs = cursor.getLong(dateTakenIndex),
                                    dateAddedSeconds = cursor.getLong(dateAddedIndex)
                                ),
                                fileName = cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty()
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure { error ->
            Log.e("PicMe:Gallery", "Failed to query system images", error)
        }.getOrDefault(emptyList())
    }

    private fun queryVideosFromMediaStore(): List<MediaAsset> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                buildList {
                    while (cursor.moveToNext()) {
                        val mediaStoreId = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId
                        )
                        add(
                            MediaAsset(
                                id = syntheticMediaId(mediaStoreId, MediaType.VIDEO),
                                uri = uri.toString(),
                                type = MediaType.VIDEO,
                                captureDate = resolveCaptureDate(
                                    dateTakenMs = cursor.getLong(dateTakenIndex),
                                    dateAddedSeconds = cursor.getLong(dateAddedIndex)
                                ),
                                fileName = cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty(),
                                duration = cursor.getLong(durationIndex).takeIf { duration -> duration > 0L }
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure { error ->
            Log.e("PicMe:Gallery", "Failed to query system videos", error)
        }.getOrDefault(emptyList())
    }

    private fun mergeMedia(dbMedia: List<MediaAsset>, systemMedia: List<MediaAsset>): List<MediaAsset> {
        val dbByUri = dbMedia.associateBy { asset -> asset.uri }
        val systemUris = systemMedia.map { asset -> asset.uri }.toHashSet()

        val mergedSystemMedia = systemMedia.map { systemAsset ->
            val dbAsset = dbByUri[systemAsset.uri] ?: return@map systemAsset
            systemAsset.copy(
                id = dbAsset.id,
                type = dbAsset.type,
                captureDate = if (systemAsset.captureDate > 0L) systemAsset.captureDate else dbAsset.captureDate,
                fileName = if (systemAsset.fileName.isNotBlank()) systemAsset.fileName else dbAsset.fileName,
                duration = systemAsset.duration ?: dbAsset.duration,
                hasFace = dbAsset.hasFace,
                faceId = dbAsset.faceId,
                source = dbAsset.source
            )
        }

        val localOnlyMedia = dbMedia.filterNot { asset -> asset.uri in systemUris }
        return (mergedSystemMedia + localOnlyMedia).sortedByDescending { asset -> asset.captureDate }
    }

    private fun deleteSystemMedia(uriString: String) {
        val uri = uriString.toUri()
        if (uri.scheme != "content") {
            return
        }
        runCatching {
            appContext.contentResolver.delete(uri, null, null)
        }.onFailure { error ->
            Log.w("PicMe:Gallery", "Failed to delete media from system gallery: $uriString", error)
        }
    }

    private fun hasImageReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasVideoReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveCaptureDate(dateTakenMs: Long, dateAddedSeconds: Long): Long {
        return when {
            dateTakenMs > 0L -> dateTakenMs
            dateAddedSeconds > 0L -> dateAddedSeconds * 1000L
            else -> 0L
        }
    }

    private fun syntheticMediaId(mediaStoreId: Long, mediaType: MediaType): Long {
        val typeSalt = when (mediaType) {
            MediaType.VIDEO -> 2L
            else -> 1L
        }
        return -((mediaStoreId * 10L) + typeSalt)
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
