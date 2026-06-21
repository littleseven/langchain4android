package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    fun getAllMedia(): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(mediaEntity: MediaEntity): Long

    @Delete
    suspend fun deleteMedia(mediaEntity: MediaEntity)

    @Query("DELETE FROM media_assets WHERE id IN (:ids)")
    suspend fun deleteMediaByIds(ids: List<Long>)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getMediaById(id: Long): MediaEntity?

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>

    // ── 搜索查询 ─────────────────────────────────────────────

    /** 按标签搜索（labels JSON 数组模糊匹配） */
    @Query("SELECT * FROM media_assets WHERE labels LIKE '%' || :label || '%' ORDER BY captureDate DESC")
    suspend fun searchByLabel(label: String): List<MediaEntity>

    /** 按 OCR 文本搜索 */
    @Query("SELECT * FROM media_assets WHERE ocrText LIKE '%' || :query || '%' ORDER BY captureDate DESC")
    suspend fun searchByOcrText(query: String): List<MediaEntity>

    /** 按地名搜索 */
    @Query("SELECT * FROM media_assets WHERE locationName LIKE '%' || :place || '%' ORDER BY captureDate DESC")
    suspend fun searchByLocation(place: String): List<MediaEntity>

    /** 按文件名搜索 */
    @Query("SELECT * FROM media_assets WHERE fileName LIKE '%' || :name || '%' ORDER BY captureDate DESC")
    suspend fun searchByFileName(name: String): List<MediaEntity>

    /** 按时间范围搜索 */
    @Query("SELECT * FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs ORDER BY captureDate DESC")
    suspend fun searchByTimeRange(startMs: Long, endMs: Long): List<MediaEntity>

    /** 综合搜索：标签 + OCR + 地名 + 文件名 */
    @Query(
        """
        SELECT * FROM media_assets WHERE
            labels LIKE '%' || :query || '%' OR
            ocrText LIKE '%' || :query || '%' OR
            locationName LIKE '%' || :query || '%' OR
            fileName LIKE '%' || :query || '%'
        ORDER BY captureDate DESC
        """
    )
    suspend fun searchAll(query: String): List<MediaEntity>

    /** 获取未索引的媒体（indexed_at IS NULL） */
    @Query("SELECT * FROM media_assets WHERE indexedAt IS NULL ORDER BY captureDate DESC")
    suspend fun getUnindexedMedia(): List<MediaEntity>

    /** 更新索引结果 */
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            indexedAt = :indexedAt
        WHERE id = :mediaId
        """
    )
    suspend fun updateIndexResult(
        mediaId: Long,
        labels: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        indexedAt: Long
    )

    /** 获取已索引媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE indexedAt IS NOT NULL AND indexedAt > 0")
    suspend fun getIndexedCount(): Int

    /** 获取媒体总数 */
    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun getTotalCount(): Int

    // ── 人脸聚类查询 ──────────────────────────────────────────

    /** 获取所有媒体（非 Flow，用于后台） */
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    suspend fun getAllMediaNow(): List<MediaEntity>

    /** 更新 hasFace */
    @Query("UPDATE media_assets SET hasFace = :hasFace WHERE id = :mediaId")
    suspend fun updateHasFace(mediaId: Long, hasFace: Boolean)

    /** 获取有脸但未聚类的媒体 */
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 AND (faceId IS NULL OR faceId = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaces(): List<MediaEntity>

    /** 更新 faceId */
    @Query("UPDATE media_assets SET faceId = :faceId WHERE id = :mediaId")
    suspend fun updateFaceId(mediaId: Long, faceId: String)

    /** 按 hasFace 搜索 */
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC")
    suspend fun searchByHasFace(): List<MediaEntity>

    /** 按 faceId 分组统计数据 */
    @Query("SELECT faceId, COUNT(*) as cnt FROM media_assets WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId ORDER BY cnt DESC")
    suspend fun getFaceGroups(): List<FaceGroupCount>

    /** 获取某个 faceId 下的所有媒体 */
    @Query("SELECT * FROM media_assets WHERE faceId = :faceId ORDER BY captureDate DESC")
    suspend fun getMediaByFaceId(faceId: Int): List<MediaEntity>
}

data class FaceGroupCount(
    val faceId: Int,
    val cnt: Int
)
