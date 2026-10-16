package com.mklhc.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource as ComposeStringResource

/**
 * A piece of user-facing text that is either a plain dynamic string or a localised string resource.
 *
 * Business logic: ViewModels never touch Android's `R.string` (this is Compose Multiplatform, there is no
 * such thing in commonMain) and never resolve resources themselves. They put a [UiText] in screen state,
 * for example an error to show, and the screen renders it with [asString]. That keeps the ViewModel pure
 * Kotlin and unit-testable on the JVM, and keeps every user-visible string localisable.
 *
 * How the resource variant is modelled: the Compose Multiplatform resources library generates, for each
 * entry of a module's `composeResources/values/strings.xml`, a `StringResource` token under that module's
 * `Res.string`. [StringResource] simply carries that token plus the format arguments, so any module that
 * owns strings can build one and any screen can render it; the token is self-describing (it knows its own
 * resource file), so the rendering side needs no knowledge of which module the text came from.
 *
 * Use [DynamicString] for values that are always runtime data (a serial number, a text the kart sent) and
 * [StringResource] for everything that is, or may become, a translated sentence. Both are data classes so
 * identical texts compare equal, which keeps a `StateFlow` of screen state from re-emitting needlessly.
 *
 * Annotated [Immutable] so the Compose compiler treats every implementation as stable: callers must only
 * pass immutable values (numbers, strings) as format arguments.
 */
@Immutable
sealed interface UiText {

    /**
     * A string that is already final text and is shown exactly as is.
     *
     * @property value the text to show.
     */
    data class DynamicString(val value: String) : UiText

    /**
     * A localised string resource and the arguments substituted into its `%1$s`-style placeholders.
     *
     * @property resource the generated resource token, for example `Res.string.error_unknown`.
     * @property args the format arguments, substituted by position through `toString()`; keep them immutable.
     */
    data class StringResource(
        val resource: ComposeStringResource,
        val args: List<Any> = emptyList(),
    ) : UiText
}

/**
 * Resolves this text to the string to render, in the current locale.
 *
 * Business logic: a [UiText.DynamicString] is returned as is; a [UiText.StringResource] is loaded through
 * `stringResource` and formatted with its args. On the JVM and Android the resource load is synchronous, so
 * the returned string is final in the same composition.
 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.DynamicString -> value
    is UiText.StringResource -> if (args.isEmpty()) {
        stringResource(resource)
    } else {
        stringResource(resource, *args.toTypedArray())
    }
}

/**
 * Resolves this text to a string outside composition, in the system locale.
 *
 * Business logic: for the rare places that need the final text without a composition, such as a log line or a
 * system notification; screens should use [asString]. It reads the same resource files, so the two always agree.
 */
suspend fun UiText.resolve(): String = when (this) {
    is UiText.DynamicString -> value
    is UiText.StringResource -> if (args.isEmpty()) {
        getString(resource)
    } else {
        getString(resource, *args.toTypedArray())
    }
}
