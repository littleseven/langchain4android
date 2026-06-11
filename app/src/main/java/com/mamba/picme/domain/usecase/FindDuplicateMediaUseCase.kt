package com.mamba.picme.domain.usecase

import com.mamba.picme.core.common.DuplicateImageDetector
import com.mamba.picme.domain.model.DuplicateGroup
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 查找重复图片用例
 */
class FindDuplicateMediaUseCase(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allAssets = repository.allMedia.firstOrNull() ?: return@withContext emptyList()

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
        val duplicateGroups = DuplicateImageDetector.findDuplicates(files)

        // 转换为领域模型（使用 URI 而不是 File）
        duplicateGroups.map { group ->
            DuplicateGroup(
                id = group.hash,
                fileUris = group.files.map { "file://${it.absolutePath}" },
                isExactDuplicate = group.isExactDuplicate
            )
        }
    }
}
