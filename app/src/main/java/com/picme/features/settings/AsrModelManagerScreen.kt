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
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * ASR 模型管理器 —— 独立于 LLM 模型管理器，只展示 Audio/ASR 分类的模型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrModelManagerScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }

    val allModels by viewModel.allModels.collectAsState()
    val downloadStates by downloadManager.downloadStates.collectAsState()
    val tagTranslations by viewModel.tagTranslations.collectAsState()

    // 过滤出 ASR/Audio 相关模型
    val asrModels = remember(allModels) {
        allModels.filter { model ->
            model.tags.any { tag ->
                tag.equals("ASR", ignoreCase = true) ||
                    tag.equals("Audio", ignoreCase = true)
            } || model.id.contains("asr", ignoreCase = true) ||
                model.id.contains("zipformer", ignoreCase = true) ||
                model.id.contains("whisper", ignoreCase = true)
        }
    }

    var modelToDelete by remember { mutableStateOf<ModelConfig?>(null) }
    var modelToShowProperties by remember { mutableStateOf<ModelConfig?>(null) }

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.asr_model_manager)) },
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
            if (asrModels.isEmpty()) {
                EmptyAsrModelList()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(asrModels) { model ->
                        val downloadState = downloadStates[model.id]
                        val isPaused = downloadState?.status == DownloadStatus.PAUSED
                        ModelCardWithBadge(
                            model = model,
                            downloadState = downloadState,
                            tagTranslations = tagTranslations,
                            onDownload = {
                                if (isPaused) {
                                    coroutineScope.launch {
                                        downloadManager.resumeDownload(model.id, model).collect { }
                                    }
                                } else {
                                    coroutineScope.launch {
                                        downloadManager.downloadModel(model.id, model).collect { }
                                    }
                                }
                            },
                            onCancel = {
                                downloadManager.cancelDownload(model.id)
                            },
                            onPause = {
                                downloadManager.pauseDownload(model.id)
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
                                downloadManager.deleteModel(model.id)
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
private fun EmptyAsrModelList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Audiotrack,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_asr_models_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
