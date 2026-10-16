package com.mklhc.core.presentation

import com.mklhc.core.presentation.generated.resources.Res
import com.mklhc.core.presentation.generated.resources.error_unknown
import com.mklhc.core.presentation.generated.resources.error_with_detail
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [UiText]: a string that is either dynamic or a Compose Multiplatform resource token. These tests resolve
 * through the suspending [resolve] against the real `strings.xml`, which is on the desktop test classpath;
 * the composable [asString] is covered by [UiTextComposeTest].
 */
class UiTextTest {

    @Test
    fun dynamicStringResolvesToItsValue() = runTest {
        assertEquals("Battery low", UiText.DynamicString("Battery low").resolve())
    }

    @Test
    fun resourceWithoutArgsResolvesToTheResourceText() = runTest {
        assertEquals("Something went wrong.", UiText.StringResource(Res.string.error_unknown).resolve())
    }

    @Test
    fun resourceArgsAreSubstitutedIntoThePlaceholder() = runTest {
        val text = UiText.StringResource(Res.string.error_with_detail, listOf("timed out"))
        assertEquals("Something went wrong: timed out", text.resolve())
    }

    @Test
    fun nonStringArgsAreFormattedWithToString() = runTest {
        val text = UiText.StringResource(Res.string.error_with_detail, listOf(42))
        assertEquals("Something went wrong: 42", text.resolve())
    }

    @Test
    fun equalTextsAreEqualSoStateDoesNotChurn() {
        // Screen state holds UiText fields; structural equality keeps a StateFlow from re-emitting an identical
        // value and keeps Compose from recomposing for nothing.
        assertEquals(UiText.DynamicString("a"), UiText.DynamicString("a"))
        assertEquals(
            UiText.StringResource(Res.string.error_with_detail, listOf("x")),
            UiText.StringResource(Res.string.error_with_detail, listOf("x")),
        )
        assertNotEquals(
            UiText.StringResource(Res.string.error_with_detail, listOf("x")),
            UiText.StringResource(Res.string.error_with_detail, listOf("y")),
        )
        assertNotEquals<UiText>(UiText.DynamicString("a"), UiText.StringResource(Res.string.error_unknown))
    }
}
