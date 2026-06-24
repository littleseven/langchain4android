package com.mamba.picme.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mamba.picme.PicMeApplication
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.PipelineStage
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TAG 生成精细控制子页面
 *
 * 功能：
 * - 展示各阶段处理进度和数据库统计
 * - 手动触发全量扫描 / 单阶段重新生成
 * - 取消进行中的扫描
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGenerationControlScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // 独立的 TagGenerationScheduler（用于全量扫描管道）
    val scheduler = remember { TagGenerationScheduler(context) }

    val faceClusterWorker = remember { app.container.faceClusteringWorker }
    val imageTagWorker = remember { app.container.imageTagIndexingWorker }

    val isScanning by scheduler.isScanning.collectAsState()
    val scanProgress by scheduler.progress.collectAsState()

    // 数据库统计（每次进入时刷新）
    var totalMedia by remember { mutableIntStateOf(0) }
    var withFace by remember { mutableIntStateOf(0) }
    var withLabels by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var embeddingCount by remember { mutableIntStateOf(0) }
    var isModelAvailable by remember { mutableStateOf(false) }

    // 操作状态
    var isClustering by remember { mutableStateOf(false) }
    var isTagging by remember { mutableStateOf(false) }

    // 刷新统计
    fun refreshStats() {
        coroutineScope.launch {
            try {
                totalMedia = db.mediaDao().getTotalCount()
                withFace = db.mediaDao().searchByHasFace().size
                withLabels = db.mediaDao().getTotalCount() - db.mediaDao().getUnlabeledMedia().size
                val persons = db.personDao().getAllPersons()
                personCount = persons.size
                embeddingCount = persons.sumOf { db.personDao().getEmbeddingCount(it.personId) }
                // 检查 MobileFaceNet 模型是否可用
                val modelDir = com.mamba.picme.data.download.ModelPathConfig.getModelDir(context, "picme-face-embedding-mnn")
                val modelFile = java.io.File(modelDir, "w600k_mbf.mnn")
                isModelAvailable = modelFile.exists() && modelFile.length() > 100_000
            } catch (_: Exception) {}
        }
    }

    // 初始加载统计
    LaunchedEffect(Unit) { refreshStats() }

    // 轮询更新（操作进行中或扫描中）
    LaunchedEffect(isScanning, isClustering, isTagging) {
        while (isScanning || isClustering || isTagging) {
            delay(2000)
            refreshStats()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TAG 生成控制") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 扫描进度卡片 ────────────────────────────
            if (isScanning && scanProgress != null) {
                ScanProgressCard(scanProgress!!)
            }

            // ── 数据库统计卡片 ────────────────────────────
            StatsCard(
                totalMedia = totalMedia,
                withFace = withFace,
                withLabels = withLabels,
                personCount = personCount,
                embeddingCount = embeddingCount,
                isModelAvailable = isModelAvailable
            )

            // ── Stage 1: 人脸 ROI 检测 ──────────────────
            StageControlCard(
                stageName = "Stage 1: 人脸 ROI 检测",
                description = "使用 InsightFace Det10G + 2D106 检测照片中的人脸位置和关键点",
                statusText = "$withFace / $totalMedia 张已检测人脸",
                statusColor = if (withFace > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                actionButtons = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                refreshStats()
                                scheduler.scanAll()
                            }
                        },
                        enabled = !isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("全量扫描中...")
                        } else {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("全量标签生成 (Stage 1→2→3)")
                        }
                    }
                }
            )

            // ── Stage 2: 人脸聚类 ────────────────────────
            StageControlCard(
                stageName = "Stage 2: 人脸聚类",
                description = "使用 MobileFaceNet 提取 512 维人脸特征向量，通过 DBSCAN 聚类去重",
                statusText = buildString {
                    append("$personCount 个人物簇, $embeddingCount 个 embedding")
                    if (!isModelAvailable) append(" | ⚠️ 模型未下载")
                },
                statusColor = when {
                    !isModelAvailable -> MaterialTheme.colorScheme.error
                    personCount > 0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                actionButtons = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isClustering = true
                                coroutineScope.launch {
                                    faceClusterWorker.forceRecluster()
                                    while (faceClusterWorker.isRunning) { delay(1000) }
                                    isClustering = false
                                    refreshStats()
                                }
                            },
                            enabled = !isClustering,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isClustering) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("聚类中...")
                            } else {
                                Icon(Icons.Rounded.FaceRetouchingNatural, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重新人脸聚类")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    db.mediaDao().resetAllFaceData()
                                    db.personDao().clearAllEmbeddings()
                                    db.personDao().clearAllPersons()
                                    refreshStats()
                                }
                            },
                            enabled = !isClustering && (personCount > 0 || withFace > 0),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空聚类数据")
                        }
                    }
                }
            )

            // ── Stage 3: Qwen 图像理解标签 ──────────────
            StageControlCard(
                stageName = "Stage 3: Qwen 图像理解标签",
                description = "使用 Qwen3.5-2B 多模态模型分析照片内容，生成中文场景/活动/物体标签",
                statusText = "$withLabels / $totalMedia 张已生成标签",
                statusColor = if (withLabels > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                actionButtons = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isTagging = true
                                coroutineScope.launch {
                                    imageTagWorker.forceReTag()
                                    while (imageTagWorker.isRunning) { delay(1000) }
                                    isTagging = false
                                    refreshStats()
                                }
                            },
                            enabled = !isTagging,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTagging) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("标记中...")
                            } else {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重新生成标签")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    db.mediaDao().resetAllLabels()
                                    refreshStats()
                                }
                            },
                            enabled = !isTagging && withLabels > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空标签数据")
                        }
                    }
                }
            )

            // ── 取消按钮（扫描中时显示）─────────────────
            AnimatedVisibility(isScanning) {
                Button(
                    onClick = { scheduler.cancel() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Cancel, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("取消全量扫描")
                }
            }

            // ── 模型状态提示 ───────────────────────────
            if (!isModelAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "MobileFaceNet 模型未下载",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "人脸聚类需要 w600k_mbf.mnn 模型。请在模型中心下载 picme-face-embedding-mnn 模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════
//  子组件
// ══════════════════════════════════════════════════════════

@Composable
private fun ScanProgressCard(progress: TagScanProgress) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "全量标签生成中",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${progress.processed} / ${progress.total} 张 (当前阶段: ${stageName(progress.currentStage)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun StatsCard(
    totalMedia: Int,
    withFace: Int,
    withLabels: Int,
    personCount: Int,
    embeddingCount: Int,
    isModelAvailable: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "数据库统计",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总照片", totalMedia.toString())
                StatItem("含人脸", withFace.toString())
                StatItem("有标签", withLabels.toString())
                StatItem("人物簇", personCount.toString())
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem("Embedding", embeddingCount.toString())
                StatItem(
                    "MFNet模型",
                    if (isModelAvailable) "已就绪 ✓" else "未下载 ✗",
                    valueColor = if (isModelAvailable) Color(0xFF4CAF50) else Color(0xFFFF5722)
                )
            }
        }
    }
}

@Composable
private fun RowScope.StatItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun StageControlCard(
    stageName: String,
    description: String,
    statusText: String,
    statusColor: Color,
    actionButtons: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stageName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            actionButtons()
        }
    }
}

private fun stageName(stage: PipelineStage): String = when (stage) {
    PipelineStage.FACE_ROI -> "人脸检测"
    PipelineStage.FACE_CLUSTER -> "人脸聚类"
    PipelineStage.QWEN_TAGGING -> "内容标签"
    PipelineStage.COMPLETE -> "完成"
}
