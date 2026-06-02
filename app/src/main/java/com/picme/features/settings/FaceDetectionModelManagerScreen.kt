package com.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.picme.R
import com.picme.data.download.DownloadStatus
import com.picme.data.download.LlmModelDownloadManager
import com.picme.data.download.ModelConfig
import kotlinx.coroutines.launch

/**
 * 人脸检测模型管理器 —— 独立于 LLM/ASR 模型管理器，只展示人脸检测相关模型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceDetectionModelManagerScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val allModels by viewModel.allModels.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val tagTranslations by viewModel.tagTranslations.collectAsState()

    // 过滤出人脸检测相关模型
    val faceDetectionModels = remember(allModels) {
        allModels.filter { model ->
            model.tags.any { tag ->
                tag.equals("face", ignoreCase = true) ||
                    tag.equals("detection", ignoreCase = true) ||
                    tag.equals("landmark", ignoreCase = true)
            } || model.id.contains("face", ignoreCase = true)
        }
    }

    // 按功能分类：ROI 检测模型 vs Landmark 检测模型
    val roiModels = remember(faceDetectionModels) {
        faceDetectionModels.filter { model ->
            model.tags.any { it.equals("detection", ignoreCase = true) } ||
                model.id.contains("det", ignoreCase = true)
        }
    }
    val landmarkModels = remember(faceDetectionModels) {
        faceDetectionModels.filter { model ->
            model.tags.any { it.equals("landmark", ignoreCase = true) } ||
                model.id.contains("landmark", ignoreCase = true)
        }
    }

    // Tab 分类
    val tabs = listOf("ROI 检测", "关键点检测")
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val currentModels = when (selectedTabIndex) {
        0 -> roiModels
        1 -> landmarkModels
        else -> faceDetectionModels
    }

    var modelToDelete by remember { mutableStateOf<ModelConfig?>(null) }
    var modelToShowProperties by remember { mutableStateOf<ModelConfig?>(null) }

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.face_detection_model_manager)) },
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
            if (faceDetectionModels.isEmpty()) {
                EmptyFaceDetectionModelList()
            } else {
                // Tab 切换栏
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentModels) { model ->
                        val downloadState = downloadStates[model.id]
                        val isPaused = downloadState?.status == DownloadStatus.PAUSED
                        ModelCardWithBadge(
                            model = model,
                            downloadState = downloadState,
                            tagTranslations = tagTranslations,
                            onDownload = {
                                if (isPaused) {
                                    viewModel.downloadModel(model.id, model)
                                } else {
                                    viewModel.downloadModel(model.id, model)
                                }
                            },
                            onCancel = {
                                // cancel/pause/delete 需通过 ViewModel 暴露，当前先保持原样
                                // TODO: 将 cancel/pause/delete 也移到 ViewModel
                            },
                            onPause = {
                                // TODO: 将 pause 移到 ViewModel
                            },
                            onDelete = { modelToDelete = model },
                            onShowProperties = { modelToShowProperties = model }
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
                        coroutineScope.launch {
                            modelToDelete?.let { model ->
                                // TODO: 将 delete 移到 ViewModel
                                viewModel.refreshModels()
                            }
                            modelToDelete = null
                        }
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

    // 模型属性对话框
    if (modelToShowProperties != null) {
        ModelPropertiesDialog(
            model = modelToShowProperties!!,
            onDismiss = { modelToShowProperties = null }
        )
    }
}

@Composable
private fun EmptyFaceDetectionModelList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Face,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_face_detection_models_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
