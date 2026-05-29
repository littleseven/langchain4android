package com.picme.domain.agent.remote

import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext

/**
 * 自适应推理策略选择器
 *
 * 根据用户输入特征自动选择最合适的远程推理层级：
 * - L1_Cached: 本地意图缓存命中
 * - L2_BatchFC: 单轮多指令（默认模式）
 * - L3_PlanExecute: 条件/依赖/多步骤
 * - L4_ReAct: 开放式探索/兜底
 */
class AdaptiveStrategySelector {

    private val intentCache = IntentCache()

    /**
     * 选择推理策略
     *
     * @param userInput 用户自然语言输入
     * @param context 当前 Agent 上下文
     * @return 选择的推理策略
     */
    fun selectStrategy(userInput: String, context: AgentContext): InferenceStrategy {
        // 1. 先查 L1 缓存
        intentCache.match(userInput)?.let { command ->
            return InferenceStrategy.L1_Cached(command)
        }

        // 2. 分析输入特征
        val features = analyzeInput(userInput)

        return when {
            // 条件/依赖/多步骤 → L3
            features.hasConditionals || features.stepCount >= 3 ->
                InferenceStrategy.L3_PlanExecute(userInput, context)

            // 多指令但无依赖 → L2
            features.commandCount >= 2 ->
                InferenceStrategy.L2_BatchFC(userInput, context)

            // 单指令 → L2（默认，LLM 统一走 Batch 格式）
            features.commandCount == 1 ->
                InferenceStrategy.L2_BatchFC(userInput, context)

            // 开放式/探索性 → L4
            features.isOpenEnded || features.isQuestion ->
                InferenceStrategy.L4_ReAct(userInput, context)

            // 兜底：走 L2
            else -> InferenceStrategy.L2_BatchFC(userInput, context)
        }
    }

    /**
     * 获取意图缓存（供外部访问统计信息）
     */
    fun getIntentCache(): IntentCache = intentCache

    private fun analyzeInput(input: String): InputFeatures {
        val trimmed = input.trim()
        return InputFeatures(
            hasConditionals = CONDITION_KEYWORDS.any { trimmed.contains(it) },
            stepCount = STEP_KEYWORDS.count { trimmed.contains(it) } + 1,
            commandCount = estimateCommandCount(trimmed),
            isOpenEnded = OPEN_ENDED_PATTERNS.any { trimmed.matches(it) },
            isQuestion = trimmed.endsWith("?") || trimmed.endsWith("？") ||
                QUESTION_WORDS.any { trimmed.contains(it) }
        )
    }

    private fun estimateCommandCount(input: String): Int {
        var count = 0
        // 通过动作关键词估算命令数量
        for (keyword in ACTION_KEYWORDS) {
            if (input.contains(keyword)) count++
        }
        // 通过步骤关键词估算
        count += STEP_KEYWORDS.count { input.contains(it) }
        return maxOf(1, count)
    }

    companion object {
        // 条件关键词
        val CONDITION_KEYWORDS = listOf("如果", "假如", "要是", "除非", "否则", "不然", "if", "unless")

        // 步骤关键词
        val STEP_KEYWORDS = listOf("先", "然后", "接着", "再", "最后", "之后", "第一步", "第二步")

        // 疑问词
        val QUESTION_WORDS = listOf("什么", "怎么", "哪些", "多少", "吗", "呢", "为什么", "如何")

        // 开放式模式
        val OPEN_ENDED_PATTERNS = listOf(
            Regex(".*你能.*"),
            Regex(".*你会.*"),
            Regex(".*有什么.*"),
            Regex(".*介绍一下.*"),
            Regex(".*help.*", RegexOption.IGNORE_CASE),
            Regex(".*what can.*", RegexOption.IGNORE_CASE)
        )

        // 动作关键词（用于估算命令数量）
        val ACTION_KEYWORDS = listOf(
            "拍照", "拍", "切", "换", "调", "设置", "打开", "关闭", "翻转",
            "capture", "switch", "adjust", "set", "open", "close", "flip",
            "磨皮", "美白", "瘦脸", "大眼", "滤镜", "风格", "模式", "变焦"
        )
    }
}

/**
 * 推理策略密封类
 */
sealed class InferenceStrategy {
    /**
     * L1: 本地意图缓存命中
     */
    data class L1_Cached(val command: AgentCommand) : InferenceStrategy()

    /**
     * L2: Batch Function Calling（单轮多指令）
     */
    data class L2_BatchFC(val userInput: String, val context: AgentContext) : InferenceStrategy()

    /**
     * L3: Plan-and-Execute（条件/依赖/多步骤）
     */
    data class L3_PlanExecute(val userInput: String, val context: AgentContext) : InferenceStrategy()

    /**
     * L4: ReAct（开放式探索/兜底）
     */
    data class L4_ReAct(val userInput: String, val context: AgentContext) : InferenceStrategy()
}

/**
 * 输入特征分析结果
 */
data class InputFeatures(
    val hasConditionals: Boolean,
    val stepCount: Int,
    val commandCount: Int,
    val isOpenEnded: Boolean,
    val isQuestion: Boolean
)
