package com.picme.domain.agent

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.data.remote.kimi.KimiCodingApiClient
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.model.InferenceResult
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.agent.remote.RemoteOrchestrator
import com.picme.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RemoteOrchestrator 集成测试
 *
 * 需要有效的 Kimi API Key 才能运行。
 * 运行前设置环境变量: KIMI_API_KEY=sk-xxx
 * 如果没有 API Key，测试会自动跳过。
 */
class RemoteOrchestratorIntegrationTest {

    private val apiKey = System.getenv("KIMI_API_KEY") ?: ""

    private val defaultContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = BeautySettings(),
        filterType = FilterType.NONE,
        styleFilter = StyleFilter.NONE,
        zoomRatio = 1f,
        exposureCompensation = 0,
        captureMode = MediaType.PHOTO,
        isRecording = false
    )

    /**
     * L2 Batch 解析 - 多命令输入
     *
     * 验证 RemoteOrchestrator 能调用 Kimi API 并将用户输入解析为多条命令。
     */
    @Test
    fun `L2 batch parsing - multiple commands`() = runBlocking {
        if (apiKey.isBlank()) {
            println("跳过集成测试：未设置 KIMI_API_KEY")
            return@runBlocking
        }

        val codingClient = KimiCodingApiClient(
            apiKey = apiKey,
            enableLogging = true
        )
        val sceneManager = SceneManager.getInstance()
        val promptBuilder = PromptBuilder(sceneManager)
        val remoteOrchestrator = RemoteOrchestrator(
            codingClient = codingClient,
            promptBuilder = promptBuilder
        )

        val result = remoteOrchestrator.processBatch(
            userInput = "调高美颜并切换冷调滤镜",
            context = defaultContext
        )

        assertTrue("结果应为 Batch 类型", result is InferenceResult.Batch)
        val batchResult = result as InferenceResult.Batch
        assertTrue("应解析出至少 2 条命令，实际=${batchResult.commands.size}", batchResult.commands.size >= 2)
    }

    /**
     * L3 计划生成 - 多步骤任务
     *
     * 验证 RemoteOrchestrator 能调用 Kimi API 并生成包含多步骤的执行计划。
     */
    @Test
    fun `L3 plan generation - multi-step task`() = runBlocking {
        if (apiKey.isBlank()) {
            println("跳过集成测试：未设置 KIMI_API_KEY")
            return@runBlocking
        }

        val codingClient = KimiCodingApiClient(
            apiKey = apiKey,
            enableLogging = true
        )
        val sceneManager = SceneManager.getInstance()
        val promptBuilder = PromptBuilder(sceneManager)
        val remoteOrchestrator = RemoteOrchestrator(
            codingClient = codingClient,
            promptBuilder = promptBuilder
        )

        val result = remoteOrchestrator.processPlan(
            userInput = "先切人像模式，再开磨皮60，然后拍3张",
            context = defaultContext
        )

        assertTrue("结果应为 Plan 类型", result is InferenceResult.Plan)
        val planResult = result as InferenceResult.Plan
        assertTrue(
            "计划应包含至少 3 个步骤，实际=${planResult.plan.steps.size}",
            planResult.plan.steps.size >= 3
        )
    }
}
