package com.mamba.picme.features.camera

import android.graphics.PointF
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [QA] 相机状态单元测试
 *
 * 整合内容（原 CameraPanelStateTest + AspectRatioAndFaceWarpTest FaceWarpParams 部分）：
 *
 * §1 CameraPanelState 面板状态机
 *    - 初始状态（全部关闭）
 *    - closePrimaryPanels() / closeBeautySubPanels() / closeAllPanels() 互斥关闭
 *    - toggleFacialRefinement() / toggleBodyManagement() 互斥开关
 *    - openMakeupEntry() 幂等切换与入口替换
 *    - 组合场景（多面板联动）
 *
 * §2 FaceWarpParams 数据模型
 *    - 默认值合理性（坐标在 [0,1] 内、眼部对称、hasFace=false）
 *    - 数据类不可变性与 copy 语义
 *    - 比例切换联动：切换后应重置 FaceWarpParams 避免旧坐标残留
 */
class CameraStateTest {

    // ================================================================
    // §1 CameraPanelState 面板状态机
    // ================================================================

    private lateinit var panelState: CameraPanelState

    @Before
    fun setUp() {
        panelState = CameraPanelState()
    }

    // --- 初始状态 ---

    @Test
    fun `panel initial state - all panels are closed`() {
        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
    }

    // --- closePrimaryPanels() ---

    @Test
    fun `closePrimaryPanels - closes all primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true
        panelState.showRatioSelector = true
        panelState.showSceneSelector = true
        panelState.showGridSelector = true

        panelState.closePrimaryPanels()

        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
        assertFalse("showBeautySelector should be closed", panelState.showBeautySelector)
        assertFalse("showRatioSelector should be closed", panelState.showRatioSelector)
        assertFalse("showSceneSelector should be closed", panelState.showSceneSelector)
        assertFalse("showGridSelector should be closed", panelState.showGridSelector)
    }

    @Test
    fun `closePrimaryPanels - does not affect beauty sub panels`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.closePrimaryPanels()

        assertTrue("showFacialRefinement should remain open", panelState.showFacialRefinement)
        assertTrue("showMakeupAdjustment should remain open", panelState.showMakeupAdjustment)
        assertTrue("showBodyManagement should remain open", panelState.showBodyManagement)
    }

    // --- closeBeautySubPanels() ---

    @Test
    fun `closeBeautySubPanels - closes all beauty sub panels`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.closeBeautySubPanels()

        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
        assertFalse("showBodyManagement should be closed", panelState.showBodyManagement)
    }

    @Test
    fun `closeBeautySubPanels - does not affect primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true

        panelState.closeBeautySubPanels()

        assertTrue("showFilterSelector should remain open", panelState.showFilterSelector)
        assertTrue("showBeautySelector should remain open", panelState.showBeautySelector)
    }

    // --- closeAllPanels() ---

    @Test
    fun `closeAllPanels - closes every panel`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true
        panelState.showRatioSelector = true
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.closeAllPanels()

        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
    }

    // --- toggleFacialRefinement() ---

    @Test
    fun `toggleFacialRefinement - opens facial refinement and closes primary panels`() {
        panelState.showFilterSelector = true
        panelState.showBeautySelector = true

        panelState.toggleFacialRefinement()

        assertTrue("showFacialRefinement should be open", panelState.showFacialRefinement)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
        assertFalse("showBeautySelector should be closed", panelState.showBeautySelector)
    }

    @Test
    fun `toggleFacialRefinement - closes makeup and body when opening facial`() {
        panelState.showMakeupAdjustment = true
        panelState.showBodyManagement = true

        panelState.toggleFacialRefinement()

        assertTrue("showFacialRefinement should be open", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
        assertFalse("showBodyManagement should be closed", panelState.showBodyManagement)
    }

    @Test
    fun `toggleFacialRefinement - second toggle closes facial refinement`() {
        panelState.toggleFacialRefinement()
        assertTrue(panelState.showFacialRefinement)

        panelState.toggleFacialRefinement()
        assertFalse("Second toggle should close facial refinement", panelState.showFacialRefinement)
    }

    // --- toggleBodyManagement() ---

    @Test
    fun `toggleBodyManagement - opens body management and closes primary panels`() {
        panelState.showFilterSelector = true

        panelState.toggleBodyManagement()

        assertTrue("showBodyManagement should be open", panelState.showBodyManagement)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
    }

    @Test
    fun `toggleBodyManagement - closes facial and makeup panels when opening`() {
        panelState.showFacialRefinement = true
        panelState.showMakeupAdjustment = true

        panelState.toggleBodyManagement()

        assertTrue("showBodyManagement should be open", panelState.showBodyManagement)
        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
        assertFalse("showMakeupAdjustment should be closed", panelState.showMakeupAdjustment)
    }

    @Test
    fun `toggleBodyManagement - second toggle closes body management`() {
        panelState.toggleBodyManagement()
        assertTrue(panelState.showBodyManagement)

        panelState.toggleBodyManagement()
        assertFalse("Second toggle should close body management", panelState.showBodyManagement)
    }

    // --- openMakeupEntry() ---

    @Test
    fun `openMakeupEntry LIP_COLOR - opens makeup adjustment and closes primary panels`() {
        panelState.showFilterSelector = true

        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)

        assertTrue("showMakeupAdjustment should be open", panelState.showMakeupAdjustment)
        assertFalse("showFilterSelector should be closed", panelState.showFilterSelector)
    }

    @Test
    fun `openMakeupEntry - closes facial refinement when opening makeup`() {
        panelState.showFacialRefinement = true

        panelState.openMakeupEntry(MakeupEntry.BLUSH)

        assertTrue("showMakeupAdjustment should be open", panelState.showMakeupAdjustment)
        assertFalse("showFacialRefinement should be closed", panelState.showFacialRefinement)
    }

    @Test
    fun `openMakeupEntry - same entry toggles off makeup panel`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        assertTrue(panelState.showMakeupAdjustment)

        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        assertFalse("Same entry should toggle off makeup panel", panelState.showMakeupAdjustment)
    }

    @Test
    fun `openMakeupEntry - different entry switches active entry without closing`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        panelState.openMakeupEntry(MakeupEntry.BLUSH)

        assertTrue("Makeup panel should remain open when switching entry", panelState.showMakeupAdjustment)
        assertTrue("Active entry should be BLUSH", panelState.activeMakeupEntry == MakeupEntry.BLUSH)
    }

    @Test
    fun `openMakeupEntry - switching through all makeup entries ends on last`() {
        panelState.openMakeupEntry(MakeupEntry.LIP_COLOR)
        panelState.openMakeupEntry(MakeupEntry.BLUSH)
        panelState.openMakeupEntry(MakeupEntry.EYEBROW)

        assertTrue(panelState.showMakeupAdjustment)
        assertTrue(panelState.activeMakeupEntry == MakeupEntry.EYEBROW)
    }

    // --- 组合场景 ---

    @Test
    fun `combined - opening beauty sub panel closes all primary panels`() {
        panelState.showFilterSelector = true
        panelState.showRatioSelector = true

        panelState.toggleFacialRefinement()

        assertFalse("Filter selector should be closed", panelState.showFilterSelector)
        assertFalse("Ratio selector should be closed", panelState.showRatioSelector)
        assertTrue("Facial refinement should be open", panelState.showFacialRefinement)
    }

    @Test
    fun `combined - closeAllPanels resets entire state`() {
        panelState.showBeautySelector = true
        panelState.showFacialRefinement = true
        panelState.openMakeupEntry(MakeupEntry.BLUSH)
        panelState.toggleBodyManagement()

        panelState.closeAllPanels()

        assertFalse(panelState.showFilterSelector)
        assertFalse(panelState.showBeautySelector)
        assertFalse(panelState.showRatioSelector)
        assertFalse(panelState.showSceneSelector)
        assertFalse(panelState.showGridSelector)
        assertFalse(panelState.showFacialRefinement)
        assertFalse(panelState.showMakeupAdjustment)
        assertFalse(panelState.showBodyManagement)
    }

    @Test
    fun `combined - toggleMakeupAdjustment delegates to openMakeupEntry with active entry`() {
        panelState.openMakeupEntry(MakeupEntry.EYEBROW)
        panelState.showMakeupAdjustment = false

        panelState.toggleMakeupAdjustment()

        assertTrue("toggleMakeupAdjustment should reopen with active entry", panelState.showMakeupAdjustment)
        assertTrue("Active entry should still be EYEBROW", panelState.activeMakeupEntry == MakeupEntry.EYEBROW)
    }

    // ================================================================
    // §2 FaceWarpParams 数据模型
    // ================================================================

    @Test
    fun `FaceWarpParams default hasFace is false`() {
        assertFalse("Default hasFace should be false", FaceWarpParams().hasFace)
    }

    @Test
    fun `FaceWarpParams default face center is at canvas center (0_5, 0_5)`() {
        val params = FaceWarpParams()
        assertEquals("Default faceCenterX should be 0.5", 0.5f, params.faceCenterX, 0.001f)
        assertEquals("Default faceCenterY should be 0.5", 0.5f, params.faceCenterY, 0.001f)
    }

    @Test
    fun `FaceWarpParams default eye positions are symmetric about center`() {
        val params = FaceWarpParams()
        assertTrue("Left eye X should be left of center", params.leftEyeX < params.faceCenterX)
        assertTrue("Right eye X should be right of center", params.rightEyeX > params.faceCenterX)
        val leftDist = params.faceCenterX - params.leftEyeX
        val rightDist = params.rightEyeX - params.faceCenterX
        assertEquals("Left and right eyes should be symmetric about center", leftDist, rightDist, 0.001f)
    }

    @Test
    fun `FaceWarpParams default contour lists are empty`() {
        val params = FaceWarpParams()
        assertTrue("Default contourPoints should be empty", params.contourPoints.isEmpty())
        assertTrue("Default leftEyeContourPoints should be empty", params.leftEyeContourPoints.isEmpty())
        assertTrue("Default rightEyeContourPoints should be empty", params.rightEyeContourPoints.isEmpty())
        assertTrue("Default lipOuterContourPoints should be empty", params.lipOuterContourPoints.isEmpty())
        assertTrue("Default lipInnerContourPoints should be empty", params.lipInnerContourPoints.isEmpty())
    }

    @Test
    fun `FaceWarpParams default faceRadius is in reasonable range (0_1 to 0_5)`() {
        val params = FaceWarpParams()
        assertTrue("Default faceRadius should be > 0.1", params.faceRadius > 0.1f)
        assertTrue("Default faceRadius should be < 0.5", params.faceRadius < 0.5f)
    }

    @Test
    fun `FaceWarpParams default mouth center is below eye level`() {
        val params = FaceWarpParams()
        assertTrue("Mouth center Y should be below eye Y", params.mouthCenterY > params.leftEyeY)
    }

    @Test
    fun `FaceWarpParams all default coordinates are within 0 to 1`() {
        val params = FaceWarpParams()
        val coords = listOf(
            "faceCenterX" to params.faceCenterX, "faceCenterY" to params.faceCenterY,
            "leftEyeX" to params.leftEyeX, "leftEyeY" to params.leftEyeY,
            "rightEyeX" to params.rightEyeX, "rightEyeY" to params.rightEyeY,
            "mouthCenterX" to params.mouthCenterX, "mouthCenterY" to params.mouthCenterY,
            "upperLipCenterX" to params.upperLipCenterX, "upperLipCenterY" to params.upperLipCenterY,
            "lowerLipCenterX" to params.lowerLipCenterX, "lowerLipCenterY" to params.lowerLipCenterY
        )
        coords.forEach { (name, value) ->
            assertTrue("$name ($value) should be >= 0", value >= 0f)
            assertTrue("$name ($value) should be <= 1", value <= 1f)
        }
    }

    @Test
    fun `FaceWarpParams is immutable - copy creates new instance with independent fields`() {
        val original = FaceWarpParams()
        val modified = original.copy(hasFace = true, faceCenterX = 0.3f)

        assertFalse("Original hasFace should not change", original.hasFace)
        assertEquals("Original faceCenterX should not change", 0.5f, original.faceCenterX, 0.001f)
        assertTrue("Modified hasFace should be true", modified.hasFace)
        assertEquals("Modified faceCenterX should be 0.3", 0.3f, modified.faceCenterX, 0.001f)
    }

    @Test
    fun `FaceWarpParams equality - same values are equal`() {
        val a = FaceWarpParams(hasFace = true, faceCenterX = 0.4f, faceCenterY = 0.6f)
        val b = FaceWarpParams(hasFace = true, faceCenterX = 0.4f, faceCenterY = 0.6f)
        assertEquals("Same-valued FaceWarpParams should be equal", a, b)
    }

    @Test
    fun `FaceWarpParams equality - different hasFace produces non-equal`() {
        assertNotEquals("Different hasFace should produce non-equal params",
            FaceWarpParams(hasFace = true), FaceWarpParams(hasFace = false))
    }

    @Test
    fun `FaceWarpParams hashCode is consistent for equal objects`() {
        val a = FaceWarpParams(hasFace = true, faceRadius = 0.2f)
        val b = FaceWarpParams(hasFace = true, faceRadius = 0.2f)
        assertEquals("Equal FaceWarpParams should have equal hashCodes", a.hashCode(), b.hashCode())
    }

    @Test
    fun `FaceWarpParams out-of-range coordinates are stored as-is (clamping is caller responsibility)`() {
        val params = FaceWarpParams(faceCenterX = 1.5f, faceCenterY = -0.1f)
        assertEquals("Out-of-range X stored as-is", 1.5f, params.faceCenterX, 0.001f)
        assertEquals("Out-of-range Y stored as-is", -0.1f, params.faceCenterY, 0.001f)
    }

    @Test
    fun `FaceWarpParams with contour points can be compared by value`() {
        val contour = listOf(PointF(0.1f, 0.2f), PointF(0.3f, 0.4f))
        assertEquals("Same contour points should be equal",
            FaceWarpParams(hasFace = true, contourPoints = contour),
            FaceWarpParams(hasFace = true, contourPoints = contour))
    }

    // --- 比例切换联动（原 AspectRatioAndFaceWarpTest 末尾场景） ---

    @Test
    fun `aspect ratio switch - default FaceWarpParams has hasFace false (prevents stale overlay)`() {
        // 切换比例时应重置为默认 FaceWarpParams，hasFace=false 避免旧坐标显示
        assertFalse("Reset params should not show stale face debug overlay", FaceWarpParams().hasFace)
    }

    @Test
    fun `aspect ratio switch - new detection result updates all coordinates`() {
        val staleParams = FaceWarpParams(hasFace = true, faceCenterX = 0.8f, faceCenterY = 0.3f)
        // 切换比例 → 重置
        val resetParams = FaceWarpParams()
        // 新比例下检测到人脸
        val newParams = resetParams.copy(hasFace = true, faceCenterX = 0.5f, faceCenterY = 0.5f)

        assertNotEquals("New params should differ from stale params", staleParams, newParams)
        assertTrue("New params should have hasFace=true", newParams.hasFace)
        assertEquals("New face center X should be updated", 0.5f, newParams.faceCenterX, 0.001f)
    }
}

