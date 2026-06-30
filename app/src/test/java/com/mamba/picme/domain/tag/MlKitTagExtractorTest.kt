package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTagExtractorTest {

    @Test
    fun `filterLabels keeps only labels above confidence threshold and sorts by confidence`() {
        val raw = listOf(
            "Food" to 0.82f,
            "Plant" to 0.91f,
            "Outdoor" to 0.47f,
            "Sky" to 0.60f,
            "Person" to 0.55f,
            "Building" to 0.30f,
            "Car" to 0.78f
        )

        val result = MlKitTagExtractor.filterLabels(raw, confidenceThreshold = 0.5f, maxLabels = 5)

        assertEquals(listOf("Plant", "Food", "Car", "Sky", "Person"), result)
    }

    @Test
    fun `filterLabels returns empty list when all below threshold`() {
        val raw = listOf("A" to 0.1f, "B" to 0.2f)
        val result = MlKitTagExtractor.filterLabels(raw, confidenceThreshold = 0.5f, maxLabels = 5)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toJsonArray serializes labels to JSON array string`() {
        val labels = listOf("Outdoor", "Food", "Plant")
        assertEquals("[\"Outdoor\",\"Food\",\"Plant\"]", MlKitTagExtractor.toJsonArray(labels))
    }

    @Test
    fun `toJsonArray returns empty array for empty list`() {
        assertEquals("[]", MlKitTagExtractor.toJsonArray(emptyList()))
    }
}
