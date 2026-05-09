package com.picme.beauty.api.facedetect

import android.graphics.PointF
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FaceWarpParams 数据契约测试
 *
 * 防止 AI Coding 修改时意外删除字段、改变默认值或破坏 copy 语义。
 * 这些测试不验证业务逻辑，只验证数据结构的稳定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceWarpParamsContractTest {

    @Test
    fun defaultValues_areCorrect() {
        val defaults = FaceWarpParams()

        assertEquals("faceCenterX default", 0.5f, defaults.faceCenterX, 0.0001f)
        assertEquals("faceCenterY default", 0.5f, defaults.faceCenterY, 0.0001f)
        assertEquals("leftEyeX default", 0.4f, defaults.leftEyeX, 0.0001f)
        assertEquals("leftEyeY default", 0.45f, defaults.leftEyeY, 0.0001f)
        assertEquals("rightEyeX default", 0.6f, defaults.rightEyeX, 0.0001f)
        assertEquals("rightEyeY default", 0.45f, defaults.rightEyeY, 0.0001f)
        assertEquals("mouthCenterX default", 0.5f, defaults.mouthCenterX, 0.0001f)
        assertEquals("mouthCenterY default", 0.62f, defaults.mouthCenterY, 0.0001f)
        assertEquals("mouthLeftX default", 0.42f, defaults.mouthLeftX, 0.0001f)
        assertEquals("mouthLeftY default", 0.62f, defaults.mouthLeftY, 0.0001f)
        assertEquals("mouthRightX default", 0.58f, defaults.mouthRightX, 0.0001f)
        assertEquals("mouthRightY default", 0.62f, defaults.mouthRightY, 0.0001f)
        assertEquals("upperLipCenterX default", 0.5f, defaults.upperLipCenterX, 0.0001f)
        assertEquals("upperLipCenterY default", 0.60f, defaults.upperLipCenterY, 0.0001f)
        assertEquals("lowerLipCenterX default", 0.5f, defaults.lowerLipCenterX, 0.0001f)
        assertEquals("lowerLipCenterY default", 0.66f, defaults.lowerLipCenterY, 0.0001f)
        assertEquals("faceRadius default", 0.18f, defaults.faceRadius, 0.0001f)
        assertEquals("hasFace default", false, defaults.hasFace)
        assertEquals("detectionSource default", FaceDetectionSource.NONE, defaults.detectionSource)
        assertEquals("requestedDetectionEngineMode default", EngineType.MEDIAPIPE, defaults.requestedDetectionEngineMode)
        assertEquals("roiRect default", null, defaults.roiRect)
    }

    @Test
    fun defaultCollections_areEmpty() {
        val defaults = FaceWarpParams()

        assertEquals("contourPoints default empty", 0, defaults.contourPoints.size)
        assertEquals("leftEyeContourPoints default empty", 0, defaults.leftEyeContourPoints.size)
        assertEquals("rightEyeContourPoints default empty", 0, defaults.rightEyeContourPoints.size)
        assertEquals("lipOuterContourPoints default empty", 0, defaults.lipOuterContourPoints.size)
        assertEquals("lipInnerContourPoints default empty", 0, defaults.lipInnerContourPoints.size)
        assertEquals("leftCheekContourPoints default empty", 0, defaults.leftCheekContourPoints.size)
        assertEquals("rightCheekContourPoints default empty", 0, defaults.rightCheekContourPoints.size)
    }

    @Test
    fun defaultBigBeautyLandmarks_areEmpty() {
        val defaults = FaceWarpParams()

        assertEquals("bigBeautyLandmarks.points default empty", 0, defaults.bigBeautyLandmarks.points.size)
        assertEquals("bigBeautyLandmarks.hasFace default false", false, defaults.bigBeautyLandmarks.hasFace)
    }

    @Test
    fun defaultAllContours_areEmpty() {
        val defaults = FaceWarpParams()

        assertEquals("allContours.totalPointCount default", 0, defaults.allContours.totalPointCount())
    }

    @Test
    fun copy_createsIndependentInstance() {
        val original = FaceWarpParams(faceCenterX = 0.3f, contourPoints = listOf(PointF(0.1f, 0.2f)))
        val copy = original.copy()

        assertNotSame("copy() should create a new instance", original, copy)
        assertEquals("copy should have same values", original, copy)
    }

    @Test
    fun copy_canModifyEveryPrimitiveField() {
        val original = FaceWarpParams()

        // 验证 copy() 可以修改每个 primitive 字段，且原对象不受影响
        assertEquals(0.99f, original.copy(faceCenterX = 0.99f).faceCenterX, 0.0001f)
        assertEquals(0.99f, original.copy(faceCenterY = 0.99f).faceCenterY, 0.0001f)
        assertEquals(0.99f, original.copy(leftEyeX = 0.99f).leftEyeX, 0.0001f)
        assertEquals(0.99f, original.copy(leftEyeY = 0.99f).leftEyeY, 0.0001f)
        assertEquals(0.99f, original.copy(rightEyeX = 0.99f).rightEyeX, 0.0001f)
        assertEquals(0.99f, original.copy(rightEyeY = 0.99f).rightEyeY, 0.0001f)
        assertEquals(0.99f, original.copy(mouthCenterX = 0.99f).mouthCenterX, 0.0001f)
        assertEquals(0.99f, original.copy(mouthCenterY = 0.99f).mouthCenterY, 0.0001f)
        assertEquals(0.99f, original.copy(mouthLeftX = 0.99f).mouthLeftX, 0.0001f)
        assertEquals(0.99f, original.copy(mouthLeftY = 0.99f).mouthLeftY, 0.0001f)
        assertEquals(0.99f, original.copy(mouthRightX = 0.99f).mouthRightX, 0.0001f)
        assertEquals(0.99f, original.copy(mouthRightY = 0.99f).mouthRightY, 0.0001f)
        assertEquals(0.99f, original.copy(upperLipCenterX = 0.99f).upperLipCenterX, 0.0001f)
        assertEquals(0.99f, original.copy(upperLipCenterY = 0.99f).upperLipCenterY, 0.0001f)
        assertEquals(0.99f, original.copy(lowerLipCenterX = 0.99f).lowerLipCenterX, 0.0001f)
        assertEquals(0.99f, original.copy(lowerLipCenterY = 0.99f).lowerLipCenterY, 0.0001f)
        assertEquals(0.99f, original.copy(faceRadius = 0.99f).faceRadius, 0.0001f)

        // 原对象不应被修改
        assertEquals(0.5f, original.faceCenterX, 0.0001f)
    }

    @Test
    fun copy_canModifyBooleanAndEnumFields() {
        val original = FaceWarpParams()

        assertEquals(true, original.copy(hasFace = true).hasFace)
        assertEquals(FaceDetectionSource.INSIGHTFACE, original.copy(detectionSource = FaceDetectionSource.INSIGHTFACE).detectionSource)
        assertEquals(EngineType.INSIGHTFACE, original.copy(requestedDetectionEngineMode = EngineType.INSIGHTFACE).requestedDetectionEngineMode)
    }

    @Test
    fun copy_canModifyCollectionFields() {
        val original = FaceWarpParams()
        val points = listOf(PointF(0.1f, 0.2f))

        assertEquals(1, original.copy(contourPoints = points).contourPoints.size)
        assertEquals(1, original.copy(leftEyeContourPoints = points).leftEyeContourPoints.size)
        assertEquals(1, original.copy(rightEyeContourPoints = points).rightEyeContourPoints.size)
        assertEquals(1, original.copy(lipOuterContourPoints = points).lipOuterContourPoints.size)
        assertEquals(1, original.copy(lipInnerContourPoints = points).lipInnerContourPoints.size)
        assertEquals(1, original.copy(leftCheekContourPoints = points).leftCheekContourPoints.size)
        assertEquals(1, original.copy(rightCheekContourPoints = points).rightCheekContourPoints.size)
    }

    @Test
    fun copy_canModifyComplexFields() {
        val original = FaceWarpParams()
        val landmarks = GpuPixelLandmarks(points = listOf(PointF(0.1f, 0.2f)), hasFace = true)
        val contours = FaceContourData(faceOval = listOf(PointF(0.1f, 0.2f)))
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.4f)

        assertEquals(true, original.copy(bigBeautyLandmarks = landmarks).bigBeautyLandmarks.hasFace)
        assertEquals(1, original.copy(allContours = contours).allContours.faceOval.size)
        assertEquals(0.1f, original.copy(roiRect = roi).roiRect!!.left, 0.0001f)
    }

    @Test
    fun equality_sameValues_areEqual() {
        val a = FaceWarpParams(faceCenterX = 0.3f, hasFace = true)
        val b = FaceWarpParams(faceCenterX = 0.3f, hasFace = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equality_differentValues_areNotEqual() {
        val a = FaceWarpParams(faceCenterX = 0.3f)
        val b = FaceWarpParams(faceCenterX = 0.4f)
        assertNotEquals(a, b)
    }

    @Test
    fun equality_differentCollections_areNotEqual() {
        val a = FaceWarpParams(contourPoints = listOf(PointF(0.1f, 0.2f)))
        val b = FaceWarpParams(contourPoints = listOf(PointF(0.3f, 0.4f)))
        assertNotEquals(a, b)
    }

    @Test
    fun equality_differentEnums_areNotEqual() {
        val a = FaceWarpParams(requestedDetectionEngineMode = EngineType.MEDIAPIPE)
        val b = FaceWarpParams(requestedDetectionEngineMode = EngineType.INSIGHTFACE)
        assertNotEquals(a, b)
    }

    @Test
    fun copy_doesNotModifyOriginal() {
        val original = FaceWarpParams(faceCenterX = 0.3f)
        original.copy(faceCenterX = 0.9f)

        assertEquals("Original should not be modified by copy()", 0.3f, original.faceCenterX, 0.0001f)
    }
}
