package com.picme.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.picme.R
import com.picme.data.download.DownloadStatus
import com.picme.data.download.LlmModelDownloadManager
import com.picme.data.download.ModelConfig
import com.picme.domain.model.ModelCategory
import androidx.compose.ui.text.font.FontWeight

/**
 * 根据标签获取对应的图标
 */
@Composable
private fun getCategoryIcon(tag: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (tag) {
        "Vision" -> Icons.Outlined.Visibility
        "Think" -> Icons.Outlined.SmartToy
        "Audio", "AudioGen" -> Icons.Outlined.Audiotrack
        "ImageGen" -> Icons.Outlined.Image
        "Code" -> Icons.Outlined.Code
        "Math" -> Icons.Outlined.Functions
        "Chat" -> Icons.AutoMirrored.Outlined.Chat
        else -> Icons.Outlined.Palette
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmModelManagerScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }

    val groupedModels by viewModel.groupedModels.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val modelTypeLabels = viewModel.getModelTypeLabels()
    val downloadStates by downloadManager.downloadStates.collectAsState()
    val tagTranslations by viewModel.tagTranslations.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var modelToDelete by remember { mutableStateOf<ModelConfig?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // 同步 Tab 索引
    LaunchedEffect(currentTab, modelTypeLabels) {
        val index = modelTypeLabels.keys.indexOf(currentTab)
        selectedTabIndex = index.coerceAtLeast(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_model_manager)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 可滚动的分类 Tab 栏
            ScrollableCategoryTabs(
                categories = modelTypeLabels,
                selectedIndex = selectedTabIndex,
                onCategorySelected = { index, category ->
                    selectedTabIndex = index
                    viewModel.switchTab(category)
                }
            )

            // 模型列表
            val currentModels = groupedModels[currentTab] ?: emptyList()

            if (currentModels.isEmpty()) {
                EmptyModelList()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentModels) { model ->
                        ModelCardWithBadge(
                            model = model,
                            downloadState = downloadStates[model.id],
                            tagTranslations = tagTranslations,
                            onDownload = {
                                // TODO: 实现下载逻辑
                            },
                            onCancel = {
                                // TODO: 实现取消下载逻辑
                            },
                            onDelete = { modelToDelete = model }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_confirm, modelToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // TODO: 实现删除逻辑
                        modelToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 可滚动的分类 Tab 栏 - 使用 Chip 风格替代 TabRow，避免文字截断
 */
@Composable
private fun ScrollableCategoryTabs(
    categories: Map<ModelCategory, String>,
    selectedIndex: Int,
    onCategorySelected: (Int, ModelCategory) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.entries.forEachIndexed { index, entry ->
            val category = entry.key
            val label = entry.value
            val isSelected = selectedIndex == index

            Surface(
                onClick = { onCategorySelected(index, category) },
                modifier = Modifier.height(36.dp),
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                shadowElevation = if (isSelected) 2.dp else 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = getCategoryIcon(category.tag),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyModelList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_models_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 根据标签获取对应的颜色
 */
@Composable
private fun getTagColor(tag: String): androidx.compose.ui.graphics.Color {
    return when (tag) {
        "Think" -> MaterialTheme.colorScheme.primary
        "Vision" -> MaterialTheme.colorScheme.tertiary
        "Audio", "AudioGen" -> MaterialTheme.colorScheme.secondary
        "ImageGen" -> androidx.compose.ui.graphics.Color(0xFF9C27B0)
        "Code" -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        "Math" -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        "Chat" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
}

@Composable
private fun ModelCardWithBadge(
    model: ModelConfig,
    downloadState: com.picme.data.download.DownloadState?,
    tagTranslations: Map<String, String>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val isDownloading = downloadState?.status == DownloadStatus.DOWNLOADING
    val progress = if (downloadState != null && downloadState.totalBytes > 0) {
        downloadState.downloadedBytes.toFloat() / downloadState.totalBytes.toFloat()
    } else {
        0f
    }

    val primaryTag = model.tags.firstOrNull() ?: "Chat"
    val tagColor = getTagColor(primaryTag)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部：模型名 + 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：名称和标签
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // 标签徽章 - 紧凑样式
                        val tagLabel = tagTranslations[primaryTag] ?: primaryTag
                        TagBadge(label = tagLabel, color = tagColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 描述
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 底部信息行：大小 + 轻量版标签
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatFileSize(model.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        if (model.isSmallModel) {
                            LightweightBadge()
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 右侧：操作按钮
                ModelActionButton(
                    downloadState = downloadState,
                    isDownloading = isDownloading,
                    onDownload = onDownload,
                    onDelete = onDelete
                )
            }

            // 下载进度条
            if (isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (downloadState?.status == DownloadStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 紧凑的标签徽章
 */
@Composable
private fun TagBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * 轻量版标签
 */
@Composable
private fun LightweightBadge() {
    Text(
        text = stringResource(R.string.model_label_lightweight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 模型操作按钮
 */
@Composable
private fun ModelActionButton(
    downloadState: com.picme.data.download.DownloadState?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            isDownloading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
            downloadState?.status == DownloadStatus.COMPLETED -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.model_downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            else -> {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.download),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
