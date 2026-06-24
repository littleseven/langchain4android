package com.mamba.picme.features.gallery.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mamba.picme.PicMeApplication
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.PipelineStage
import com.mamba.picme.domain.tag.TagScanProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TAG 生成精细控制子页面
 *
 * 显示 3-Pass 混合管道的各阶段进度和数据库统计。
 * 所有操作（全量/增量扫描）通过 TagGenerationScheduler 统一管理。
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

    // TagGenerationScheduler 从 AppContainer 获取（应用级生命周期，支持后台运行）
    val scheduler = remember { app.container.tagGenerationScheduler }

    // 启动前台 Service 确保后台执行不中断
    LaunchedEffect(Unit) {
        val intent = Intent(context, TagGenerationService::class.java)
        context.startForegroundService(intent)
    }

    val isScanning by scheduler.isScanning.collectAsState()
    val scanProgress by scheduler.progress.collectAsState()
    val lastScanMessage by scheduler.lastScanMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 数据库统计（每次进入时刷新）
    var totalMedia by remember { mutableIntStateOf(0) }
    var withFace by remember { mutableIntStateOf(0) }
    var withLabels by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var embeddingCount by remember { mutableIntStateOf(0) }
    var isModelAvailable by remember { mutableStateOf(false) }

    // 刷新统计
    fun refreshStats() {
        coroutineScope.launch {
            try {
                totalMedia = db.mediaDao().getTotalCount()
                withFace = db.mediaDao().searchByHasFace().size
                withLabels = db.mediaDao().getTotalCount() - db.mediaDao().getUnlabeledMedia().size
                personCount = db.personDao().getAllPersons().size
                embeddingCount = db.personDao().getAllEmbeddingCount()
                val modelDir = com.mamba.picme.data.download.ModelPathConfig.getModelDir(context, "picme-face-embedding-mnn")
                val modelFile = java.io.File(modelDir, "w600k_mbf.mnn")
                isModelAvailable = modelFile.exists() && modelFile.length() > 100_000
            } catch (_: Exception) {}
        }
    }

    // 显示扫描完成通知
    LaunchedEffect(lastScanMessage) {
        val msg = lastScanMessage ?: return@LaunchedEffect
        refreshStats()
        snackbarHostState.showSnackbar(msg)
    }

    // 初始加载统计
    LaunchedEffect(Unit) { refreshStats() }

    // 轮询更新（扫描中进行时）
    LaunchedEffect(isScanning) {
        while (isScanning) {
            delay(2000)
            refreshStats()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // ── 3-Pass 混合管道概览卡片 ────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "3-Pass 混合管道",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withFace > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withFace > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 1: 人脸检测 + Embedding", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "InsightFace + MobileFaceNet → $withFace / $totalMedia 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (personCount > 0 || !isScanning || !isModelAvailable) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (personCount > 0 || !isScanning || !isModelAvailable) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 2: DBSCAN 全局聚类", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$personCount 个人物簇${if (!isModelAvailable) " | ⚠️ 模型未下载" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!isModelAvailable) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withLabels > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withLabels > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 3: Qwen 图像理解标签", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Qwen3.5-2B → $withLabels / $totalMedia 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (isScanning) {
                        Button(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("3-Pass 扫描中...")
                        }
                    } else {
                        // ── 全量/增量扫描按钮 ─────────────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    refreshStats()
                                    scheduler.scanAll()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全量扫描")
                            }
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    scheduler.scanIncremental()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Update, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("增量扫描")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // ── 分阶段独立控制 ────────────────
                        Text(
                            "分阶段执行",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    scheduler.scanPass1()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pass 1")
                            }
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    scheduler.scanPass2()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pass 2")
                            }
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    scheduler.scanPass3()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pass 3")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // ── Pass 3 重新生成（清空重标） ──────────
                        Button(
                            onClick = {
                                refreshStats()
                                scheduler.scanPass3Full()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pass 3 重新生成")
                        }
                    }
                }
            }

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
                    Text("取消扫描")
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
                    "3-Pass 扫描中",
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
                "${progress.processed} / ${progress.total} 张 (${passDisplayName(progress.currentStage)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun passDisplayName(stage: PipelineStage): String = when (stage) {
    PipelineStage.FACE_ROI -> "Pass 1: 人脸检测"
    PipelineStage.FACE_CLUSTER -> "Pass 2: DBSCAN 聚类"
    PipelineStage.QWEN_TAGGING -> "Pass 3: Qwen 标签"
    PipelineStage.COMPLETE -> "完成"
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
