package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass

/**
 * TAG 扫描会话增强进度
 */
data class TagScanSessionProgress(
    val sessionId: String,
    val state: ScanSessionState,
    val currentPass: TagScanPass? = null,
    val currentMediaId: Long? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val estimatedRemainingMs: Long? = null,
    val messages: List<ScanMessage> = emptyList()
)

enum class ScanSessionState {
    IDLE,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    COMPLETED
}

data class ScanMessage(
    val timestamp: Long = System.currentTimeMillis(),
    val level: MessageLevel,
    val text: String
)

enum class MessageLevel {
    INFO,
    WARNING,
    ERROR
}
