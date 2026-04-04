package com.picme.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeautyEngineRuntimeStateTest {

    @Test
    fun markAndConsume_fallbackReason_isOneShot() {
        BeautyEngineRuntimeState.markRPlanFallback("init failed")

        val first = BeautyEngineRuntimeState.consumeRPlanFallbackReason()
        val second = BeautyEngineRuntimeState.consumeRPlanFallbackReason()

        assertEquals("init failed", first)
        assertNull(second)
    }
}

