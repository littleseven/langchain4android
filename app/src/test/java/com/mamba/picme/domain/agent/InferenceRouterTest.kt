package com.mamba.picme.domain.agent

import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.inference.AdaptiveStrategySelector
import com.mamba.picme.agent.core.api.execution.ExecutionPlan
import com.mamba.picme.agent.core.runtime.inference.InferenceStrategy
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.api.context.MediaType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InferenceRouter 单元测试
 *
 * 验证路由逻辑：
 * - RESTRICTED 隐私输入强制走本地
 * - L1 缓存命中返回本地单命令
 * - L2 Batch 路由到远程批量命令
 * - L3 Plan 路由到远程计划执行
 * - L4 Chat 路由到远程聊天
 * - 远程失败时行为
 */
class InferenceRouterTest {

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

    // ------------------------------------------------------------------
    // 1. RESTRICTED 隐私输入强制路由到本地
    // ------------------------------------------------------------------

    @Test
    fun `restricted privacy input routes to local`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        // 坐标数据属于 RESTRICTED
        val restrictedInput = "100,200"

        // 本地引擎返回成功
        coEvery {
            localEngine.generateWithSystem(
                systemPrompt = any(),
                userPrompt = any(),
                maxTokens = any()
            )
        } returns Result.success("{\"action\":\"capture\"}")

        val result = router.processInput(restrictedInput, defaultContext)

        assertTrue("RESTRICTED 输入应返回 Local 结果", result is InferenceResult.Local)
        val localResult = result as InferenceResult.Local
        assertTrue("本地结果应为 CapturePhoto", localResult.command is AgentCommand.CapturePhoto)
    }

    // ------------------------------------------------------------------
    // 2. L1 缓存命中路由到本地单命令
    // ------------------------------------------------------------------

    @Test
    fun `L1 cached strategy returns local command`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        val userInput = "拍张照"
        val cachedCommand = AgentCommand.CapturePhoto

        // 策略选择器返回 L1 缓存命中
        every {
            strategySelector.selectStrategy(userInput, defaultContext)
        } returns InferenceStrategy.L1_Cached(cachedCommand)

        val result = router.processInput(userInput, defaultContext)

        assertTrue("L1 缓存应返回 Local 结果", result is InferenceResult.Local)
        val localResult = result as InferenceResult.Local
        assertEquals("L1 缓存应返回预置命令", cachedCommand, localResult.command)
    }

    // ------------------------------------------------------------------
    // 3. L2 BatchFC 路由到远程批量命令
    // ------------------------------------------------------------------

    @Test
    fun `L2 BatchFC strategy routes to remote batch`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        val userInput = "磨皮开到60，美白30，然后拍一张"
        val batchCommands = listOf(
            AgentCommand.AdjustBeauty(
                BeautySettings(enabled = true, smoothing = 60f, whitening = 30f)
            ),
            AgentCommand.CapturePhoto
        )

        // 策略选择器返回 L2
        every {
            strategySelector.selectStrategy(userInput, defaultContext)
        } returns InferenceStrategy.L2_BatchFC(userInput, defaultContext)

        // 远程编排器返回批量结果
        coEvery {
            remoteOrchestrator.processBatch(userInput, defaultContext)
        } returns InferenceResult.Batch(commands = batchCommands)

        val result = router.processInput(userInput, defaultContext)

        assertTrue("L2 策略应返回 Batch 结果", result is InferenceResult.Batch)
        val batchResult = result as InferenceResult.Batch
        assertEquals("应包含 2 条命令", 2, batchResult.commands.size)
        assertTrue("第一条应为 AdjustBeauty", batchResult.commands[0] is AgentCommand.AdjustBeauty)
        assertTrue("第二条应为 CapturePhoto", batchResult.commands[1] is AgentCommand.CapturePhoto)
    }

    // ------------------------------------------------------------------
    // 4. L3 PlanExecute 路由到远程计划执行
    // ------------------------------------------------------------------

    @Test
    fun `L3 PlanExecute strategy routes to remote plan`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        val userInput = "如果是后置摄像头就切前置，然后设置磨皮80美白60，最后拍一张"
        val plan = ExecutionPlan(
            planId = "plan_1",
            steps = emptyList(),
            description = "切换前置并拍摄人像"
        )

        // 策略选择器返回 L3
        every {
            strategySelector.selectStrategy(userInput, defaultContext)
        } returns InferenceStrategy.L3_PlanExecute(userInput, defaultContext)

        // 远程编排器返回计划结果
        coEvery {
            remoteOrchestrator.processPlan(userInput, defaultContext)
        } returns InferenceResult.Plan(plan = plan)

        val result = router.processInput(userInput, defaultContext)

        assertTrue("L3 策略应返回 Plan 结果", result is InferenceResult.Plan)
        val planResult = result as InferenceResult.Plan
        assertEquals("plan_1", planResult.plan.planId)
        assertEquals("切换前置并拍摄人像", planResult.plan.description)
    }

    // ------------------------------------------------------------------
    // 5. L4 ReAct 路由到远程聊天
    // ------------------------------------------------------------------

    @Test
    fun `L4 ReAct strategy routes to remote chat`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        val userInput = "你能做什么？"
        val chatMessage = "我是 PicMe 的 AI 助手小觅，可以帮你控制相机、调节美颜、切换滤镜等。"

        // 策略选择器返回 L4
        every {
            strategySelector.selectStrategy(userInput, defaultContext)
        } returns InferenceStrategy.L4_ReAct(userInput, defaultContext)

        // 远程编排器返回聊天结果
        coEvery {
            remoteOrchestrator.processChat(userInput, defaultContext)
        } returns InferenceResult.Chat(message = chatMessage)

        val result = router.processInput(userInput, defaultContext)

        assertTrue("L4 策略应返回 Chat 结果", result is InferenceResult.Chat)
        val chatResult = result as InferenceResult.Chat
        assertEquals(chatMessage, chatResult.message)
    }

    // ------------------------------------------------------------------
    // 6. SENSITIVE 隐私输入允许远程（非 RESTRICTED）
    // ------------------------------------------------------------------

    @Test
    fun `sensitive privacy input allows remote routing`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        // "我的照片" 是 SENSITIVE 但不是 RESTRICTED，允许远程
        val userInput = "我的照片在哪里"
        val chatMessage = "照片存储在本地相册中。"

        // 策略选择器返回 L4
        every {
            strategySelector.selectStrategy(userInput, defaultContext)
        } returns InferenceStrategy.L4_ReAct(userInput, defaultContext)

        coEvery {
            remoteOrchestrator.processChat(userInput, defaultContext)
        } returns InferenceResult.Chat(message = chatMessage)

        val result = router.processInput(userInput, defaultContext)

        assertTrue("SENSITIVE 输入应允许远程路由", result is InferenceResult.Chat)
    }

    // ------------------------------------------------------------------
    // 7. 本地引擎失败时返回 Error 命令
    // ------------------------------------------------------------------

    @Test
    fun `local engine failure returns error command`() = runBlocking {
        val localEngine = mockk<LocalLlmEngine>()
        val remoteOrchestrator = mockk<RemoteOrchestrator>()
        val strategySelector = mockk<AdaptiveStrategySelector>()
        val privacyGuard = PrivacyGuard()

        val router = InferenceRouter(
            localEngine = localEngine,
            remoteOrchestrator = remoteOrchestrator,
            strategySelector = strategySelector,
            privacyGuard = privacyGuard
        )

        val restrictedInput = "100,200"

        // 本地引擎返回失败
        coEvery {
            localEngine.generateWithSystem(
                systemPrompt = any(),
                userPrompt = any(),
                maxTokens = any()
            )
        } returns Result.failure(RuntimeException("模型未加载"))

        val result = router.processInput(restrictedInput, defaultContext)

        assertTrue("本地失败应返回 Local 结果", result is InferenceResult.Local)
        val localResult = result as InferenceResult.Local
        assertTrue("失败时应返回 Error 命令", localResult.command is AgentCommand.Error)
        val errorCommand = localResult.command as AgentCommand.Error
        assertTrue("错误消息应包含失败原因", errorCommand.reason.contains("本地推理失败"))
    }
}
