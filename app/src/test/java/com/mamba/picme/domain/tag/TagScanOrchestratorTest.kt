package com.mamba.picme.domain.tag

import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TAG 扫描编排器单元测试
 */
class TagScanOrchestratorTest {

    @Test
    fun `isPassesCovered returns true when requested is empty`() {
        assertTrue(TagScanOrchestrator.isPassesCovered(null, emptySet()))
        assertTrue(TagScanOrchestrator.isPassesCovered("{}", emptySet()))
    }

    @Test
    fun `isPassesCovered returns false when lastTagScanPasses is null or empty`() {
        assertFalse(TagScanOrchestrator.isPassesCovered(null, setOf("1")))
        assertFalse(TagScanOrchestrator.isPassesCovered("", setOf("1")))
    }

    @Test
    fun `isPassesCovered returns true only when all requested passes exist`() {
        val passes = """{"1":1000,"2":2000}"""

        assertTrue(TagScanOrchestrator.isPassesCovered(passes, setOf("1")))
        assertTrue(TagScanOrchestrator.isPassesCovered(passes, setOf("1", "2")))
        assertFalse(TagScanOrchestrator.isPassesCovered(passes, setOf("1", "2", "3")))
        assertFalse(TagScanOrchestrator.isPassesCovered(passes, setOf("3")))
    }

    @Test
    fun `isPassesCovered handles malformed json gracefully`() {
        assertFalse(TagScanOrchestrator.isPassesCovered("not-json", setOf("1")))
    }

    @Test
    fun `TagCategory toPasses maps face to pass 1 and 2`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.FACE))
        assertEquals(listOf(TagScanPass.FACE_DETECTION, TagScanPass.DBSCAN), passes)
    }

    @Test
    fun `TagCategory toPasses maps scene to pass 3`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.SCENE))
        assertEquals(listOf(TagScanPass.QWEN_TAGGING), passes)
    }

    @Test
    fun `TagCategory toPasses combines passes without duplicates`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.FACE, TagCategory.SCENE, TagCategory.TAGS))
        assertEquals(
            listOf(
                TagScanPass.FACE_DETECTION,
                TagScanPass.DBSCAN,
                TagScanPass.QWEN_TAGGING
            ),
            passes
        )
    }
}
