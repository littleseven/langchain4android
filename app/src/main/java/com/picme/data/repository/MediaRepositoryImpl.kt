package com.picme.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class MediaRepositoryImpl(
    private val mediaDao: MediaDao,
    context: Context
) : MediaRepository {

    private val appContext = context.applicationContext
    private val refreshVersion = MutableStateFlow(0)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val staleCleanupInProgress = AtomicBoolean(false)

    // 存储待删除的 URI 列表，用于权限请求后重试
    private val pendingDeleteUris = mutableListOf<Uri>()
    private var pendingRecoverableIntentSender: android.content.IntentSender? = null
    private var pendingDeleteIds: List<Long>? = null

    override val allMedia: Flow<List<MediaAsset>> = combine(
        mediaDao.getAllMedia(),
        refreshVersion
    ) { entities, _ ->
        val dbMedia = entities.map { entity -> entity.toDomain() }
        mergeMedia(dbMedia = dbMedia, systemMedia = loadSystemMedia())
    }

    /**
     * 获取待删除的 URI 列表（用于权限请求）
     */
    override fun getPendingDeleteUris(): List<Uri> {
        return pendingDeleteUris.toList()
    }

    /**
     * 清除待删除的 URI 列表
     */
    override fun clearPendingDeleteUris() {
        pendingDeleteUris.clear()
    }

    override fun getPendingRecoverableIntentSender(): android.content.IntentSender? =
        pendingRecoverableIntentSender

    override fun clearPendingRecoverable() {
        pendingRecoverableIntentSender = null
    }

    /**
     * 在用户授权后执行删除操作
     * 注意：对于 Android 11+，MediaStore.createDeleteRequest 已经处理了删除
     * 这里只需要清理数据库记录并刷新即可
     * 对于 Android 10 (API 29)，会重试之前失败的删除操作
     */
    override suspend fun executePendingDeletes() {
        val savedIds = pendingDeleteIds
        pendingDeleteIds = null

        // Android 10 (API 29): retry deletion after recoverable auth
        if (pendingRecoverableIntentSender != null && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            clearPendingRecoverable()
            if (savedIds != null) {
                Log.d("PicMe:Gallery", "Retrying deletion for API 29 after user authorization")
                deleteMediaByIds(savedIds)
            } else {
                refreshMediaLibrary()
            }
            return
        }

        if (pendingDeleteUris.isEmpty()) return

        val urisToDelete = pendingDeleteUris.toList()
        Log.d("PicMe:Gallery", "Executing pending deletes for ${urisToDelete.size} items")

        // 清除待删除列表（系统已通过 createDeleteRequest 处理删除）
        clearPendingDeleteUris()

        // 清理 Room 数据库记录（通过 URI 查找对应的本地 ID）
        withContext(Dispatchers.IO) {
            val currentAssets = allMedia.first()
            val localIds = currentAssets
                .filter { asset -> asset.uri.toUri() in urisToDelete }
                .mapNotNull { asset -> if (asset.id > 0L) asset.id else null }

            if (localIds.isNotEmpty()) {
                Log.d("PicMe:Gallery", "Cleaning up local DB records: $localIds")
                mediaDao.deleteMediaByIds(localIds)
            }

            // 刷新媒体库
            refreshMediaLibrary()
        }

        Log.d("PicMe:Gallery", "Pending deletes execution completed")
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

        // 重要：在开始新的删除操作前，清空之前的待处理列表
        // 避免多次删除操作导致 URI 累积
        if (pendingDeleteUris.isNotEmpty()) {
            Log.w("PicMe:Gallery", "Clearing ${pendingDeleteUris.size} pending URIs from previous operation")
            clearPendingDeleteUris()
        }
        clearPendingRecoverable()
        pendingDeleteIds = ids

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
        val successfullyDeletedIds = mutableListOf<Long>()
        var needsUserAuth = false
        for (asset in assetsToDelete) {
            if (needsUserAuth) {
                // 一旦检测到需要用户授权，停止处理后续资产
                // 避免后续资产删除成功但 DB 未清理导致数据不一致
                Log.d("PicMe:Gallery", "Skipping remaining deletions, waiting for user auth")
                break
            }

            Log.d("PicMe:Gallery", "Attempting to delete file: ${asset.uri}")
            val deleted = deleteSystemMedia(asset.uri)

            if (deleted) {
                successfullyDeletedIds.add(asset.id)
            } else if (pendingRecoverableIntentSender != null) {
                // Android 10 (API 29): 需要逐条授权，停止处理后续资产
                needsUserAuth = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: 一旦需要授权，收集当前及剩余所有 content:// URI，保证批量删除覆盖完整选择集。
                val pendingAuthUris = assetsToDelete
                    .dropWhile { pendingAsset -> pendingAsset.id != asset.id }
                    .map { pendingAsset -> pendingAsset.uri.toUri() }
                    .filter { pendingUri -> pendingUri.scheme == "content" }
                    .distinct()

                if (pendingAuthUris.isNotEmpty()) {
                    pendingDeleteUris.addAll(pendingAuthUris)
                    needsUserAuth = true
                    break
                } else {
                    Log.w("PicMe:Gallery", "Skip delete request for non-content URI: ${asset.uri}")
                }
            }
        }

        // 3. 只有确认物理删除成功、或不需要额外权限时，才清理 Room 数据库记录
        // 如果需要用户授权，推迟数据库清理，避免用户拒绝后出现数据不一致
        if (!needsUserAuth) {
            pendingDeleteIds = null
            val localIds = successfullyDeletedIds.filter { id -> id > 0L }
            if (localIds.isNotEmpty()) {
                Log.d("PicMe:Gallery", "Deleting local DB records: $localIds")
                mediaDao.deleteMediaByIds(localIds)
            }
            // 清理 Coil 图片缓存，避免已删除文件的缩略图残留
            val imageLoader = coil.Coil.imageLoader(appContext)
            assetsToDelete
                .filter { asset -> asset.id in successfullyDeletedIds }
                .forEach { asset ->
                    imageLoader.diskCache?.remove(asset.uri)
                    imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(asset.uri))
                }
            refreshMediaLibrary()
        }

        Log.d("PicMe:Gallery", "Deletion process completed. Pending auth: ${pendingDeleteUris.size}, Recoverable: ${pendingRecoverableIntentSender != null}")
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
        val (validLocalMedia, staleLocalMedia) = localOnlyMedia.partition { asset ->
            isAssetUriStillValid(asset.uri)
        }

        // 列表自愈：清理 DB 中残留的失效条目，避免相册出现空白占位。
        if (staleLocalMedia.isNotEmpty()) {
            scheduleStaleAssetCleanup(staleLocalMedia)
        }

        return (mergedSystemMedia + validLocalMedia).sortedByDescending { asset -> asset.captureDate }
    }

    /**
     * 尝试删除系统媒体文件
     * @return true 如果删除成功，false 如果需要用户授权
     */
    private fun scheduleStaleAssetCleanup(staleAssets: List<MediaAsset>) {
        val staleIds = staleAssets.mapNotNull { asset -> asset.id.takeIf { id -> id > 0L } }
        if (staleIds.isEmpty()) {
            return
        }
        if (!staleCleanupInProgress.compareAndSet(false, true)) {
            return
        }

        repositoryScope.launch {
            runCatching {
                Log.w("PicMe:Gallery", "Self-heal stale records: $staleIds")
                mediaDao.deleteMediaByIds(staleIds)
                val imageLoader = coil.Coil.imageLoader(appContext)
                staleAssets.forEach { asset ->
                    imageLoader.diskCache?.remove(asset.uri)
                    imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(asset.uri))
                }
                refreshVersion.value = refreshVersion.value + 1
            }.onFailure { error ->
                Log.e("PicMe:Gallery", "Failed to self-heal stale records", error)
            }
            staleCleanupInProgress.set(false)
        }
    }

    private fun isAssetUriStillValid(uriString: String): Boolean {
        val uri = uriString.toUri()
        return when (uri.scheme) {
            "content" -> contentUriStillExists(uri)
            "file" -> uri.path?.let { path -> File(path).exists() } ?: false
            null -> uriString.startsWith("/") && File(uriString).exists()
            else -> true
        }
    }

    private fun deleteSystemMedia(uriString: String): Boolean {
        val uri = uriString.toUri()
        if (uri.scheme == "file" || uri.scheme == null) {
            return deleteLocalFile(uriString, uri)
        }
        if (uri.scheme != "content") {
            Log.w("PicMe:Gallery", "Cannot delete unsupported URI: $uriString")
            return false
        }

        return try {
            val deletedRows = appContext.contentResolver.delete(uri, null, null)
            if (deletedRows > 0) {
                Log.d("PicMe:Gallery", "Successfully deleted media: $uriString")
                true
            } else {
                val uriStillExists = contentUriStillExists(uri)
                if (!uriStillExists) {
                    Log.d("PicMe:Gallery", "Media already missing, treat as deleted: $uriString")
                    true
                } else {
                    Log.w("PicMe:Gallery", "Delete returned 0 rows for existing URI: $uriString")
                    false
                }
            }
        } catch (e: SecurityException) {
            // Android 10+ 需要用户授权才能删除非应用创建的文件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? android.app.RecoverableSecurityException
                if (recoverable != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // Android 10 (API 29): 保存恢复性授权 IntentSender
                    pendingRecoverableIntentSender = recoverable.userAction.actionIntent.intentSender
                    Log.w("PicMe:Gallery", "Recoverable security exception saved for API 29: $uriString")
                } else {
                    Log.w("PicMe:Gallery", "Security exception, user authorization required for: $uriString")
                }
                false
            } else {
                Log.e("PicMe:Gallery", "Failed to delete media: $uriString", e)
                false
            }
        } catch (e: Exception) {
            Log.e("PicMe:Gallery", "Failed to delete media: $uriString", e)
            false
        }
    }

    private fun deleteLocalFile(uriString: String, uri: Uri): Boolean {
        val path = if (uri.scheme == "file") {
            uri.path
        } else {
            uriString.takeIf { raw -> raw.startsWith("/") }
        }
        if (path.isNullOrBlank()) {
            Log.w("PicMe:Gallery", "Cannot resolve local file path for URI: $uriString")
            return false
        }

        val targetFile = File(path)
        if (!targetFile.exists()) {
            Log.d("PicMe:Gallery", "Local file already missing, treat as deleted: $path")
            return true
        }

        val deleted = runCatching { targetFile.delete() }.getOrDefault(false)
        if (deleted) {
            Log.d("PicMe:Gallery", "Deleted local file: $path")
        } else {
            Log.w("PicMe:Gallery", "Failed to delete local file: $path")
        }
        return deleted
    }

    private fun contentUriStillExists(uri: Uri): Boolean {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        }.onFailure { error ->
            Log.w("PicMe:Gallery", "Failed to query URI existence: $uri", error)
        }.getOrDefault(true)
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
