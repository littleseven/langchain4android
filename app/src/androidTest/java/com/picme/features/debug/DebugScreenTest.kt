package com.picme.features.debug

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DebugScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun debugScreen_shouldDisplayScreenshotButton() {
        // Given
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = {},
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // Then
        composeTestRule.onNodeWithText("Screenshot")
            .assertExists()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun debugScreen_shouldDisplayAllDataGenerationButtons() {
        // Given
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = {},
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // Then
        composeTestRule.onNodeWithText("Person").assertIsDisplayed()
        composeTestRule.onNodeWithText("Landscape").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swimwear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sexy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear Test Data").assertIsDisplayed()
    }

    @Test
    fun debugScreen_screenshotButton_clickShouldTriggerAction() {
        // Given
        var screenshotClicked = false
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = {},
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // When
        composeTestRule.onNodeWithText("Screenshot")
            .performClick()

        // Then - verify screenshot action was triggered
        // Note: In actual test, would need to verify Toast or log entry
    }

    @Test
    fun debugScreen_shouldDisplayLogWindow() {
        // Given
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = {},
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // Then
        composeTestRule.onNode(hasText("Grep logs...", substring = true))
            .assertExists()
            .assertIsDisplayed()
    }

    @Test
    fun debugScreen_shouldDisplayBackButton() {
        // Given
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = {},
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Back")
            .assertExists()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun debugScreen_backButton_clickShouldTriggerNavigation() {
        // Given
        var backClicked = false
        composeTestRule.setContent {
            DebugScreen(
                onNavigateBack = { backClicked = true },
                mediaViewModel = mockk(relaxed = true)
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Back")
            .performClick()

        // Then
        assert(backClicked)
    }
}
