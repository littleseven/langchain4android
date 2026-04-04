package com.picme.data.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserPreferencesRepositoryRecoveryInstrumentedTest {

    private fun createRepository(): UserPreferencesRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return UserPreferencesRepository(context)
    }

    private suspend fun resetRepositoryState(repository: UserPreferencesRepository) {
        repository.triggerManualRPlanRecovery()
        repository.clearRPlanRecoveryCooldown()
    }

    @After
    fun tearDown() = runBlocking {
        resetRepositoryState(createRepository())
    }

    @Test
    fun persistFallback_thenTriggerRecovery_shouldSwitchStrategyAndCooldown() = runBlocking {
        val repository = createRepository()
        resetRepositoryState(repository)

        repository.persistRPlanFallback(cooldownMs = 60_000L)

        val fallbackStrategy = repository.beautyStrategyFlow.first()
        val fallbackRecoveryAt = repository.rPlanRecoveryAvailableAtFlow.first()

        assertEquals(BeautyStrategy.PIXEL_FREE, fallbackStrategy)
        assertTrue(fallbackRecoveryAt > System.currentTimeMillis())

        repository.triggerManualRPlanRecovery()

        val recoveredStrategy = repository.beautyStrategyFlow.first()
        val recoveredRecoveryAt = repository.rPlanRecoveryAvailableAtFlow.first()

        assertEquals(BeautyStrategy.R_PLAN, recoveredStrategy)
        assertEquals(0L, recoveredRecoveryAt)
    }
}

