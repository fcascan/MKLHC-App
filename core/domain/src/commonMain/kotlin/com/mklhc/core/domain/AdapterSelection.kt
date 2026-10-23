package com.mklhc.core.domain

/**
 * Which USB adapter the user wants the app to use.
 *
 * Business logic: the choice is persisted with the pairing data and starts as [Automatic].
 * - [Automatic]: the app picks the first compatible adapter it can open (see [resolveAdapter]).
 * - [Manual]: the app uses exactly the adapter with the chosen [UsbId], and never silently swaps it for
 *   another one: if it is missing or cannot be opened, the screen says why.
 */
sealed interface AdapterSelection {

    /** The app picks a compatible adapter by itself. */
    data object Automatic : AdapterSelection

    /** The app uses the adapter with this [id]. */
    data class Manual(val id: UsbId) : AdapterSelection
}
