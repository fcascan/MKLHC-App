package com.mklhc.core.presentation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mklhc.core.presentation.generated.resources.Res
import com.mklhc.core.presentation.generated.resources.error_with_detail
import kotlin.test.Test

/**
 * The composable side of [UiText]: [asString] must render a dynamic string as is and a resource token as its
 * formatted text. On the JVM `stringResource` loads the resource synchronously, so the text is on screen after
 * the first composition without any idling.
 */
@OptIn(ExperimentalTestApi::class)
class UiTextComposeTest {

    @Test
    fun asStringRendersADynamicString() = runComposeUiTest {
        setContent { Text(UiText.DynamicString("Battery low").asString()) }
        onNodeWithText("Battery low").assertExists()
    }

    @Test
    fun asStringRendersAFormattedResource() = runComposeUiTest {
        setContent { Text(UiText.StringResource(Res.string.error_with_detail, listOf("no signal")).asString()) }
        onNodeWithText("Something went wrong: no signal").assertExists()
    }
}
