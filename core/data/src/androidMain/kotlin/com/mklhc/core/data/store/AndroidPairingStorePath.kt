package com.mklhc.core.data.store

import android.content.Context
import java.io.File

/**
 * The path of the Android build's pairing file: [PAIRING_STORE_FILE_NAME] in the `datastore`
 * folder of the app's private files directory. Pass it to [createPairingDataStore].
 *
 * Business logic: the app-private files directory is not readable by other apps, which is what keeps
 * the stored master secret out of reach. It is unencrypted and, unless the app's backup rules exclude
 * it, eligible for Android's cloud backup: the `:app` module should exclude `datastore/` from backup
 * and device transfer so the secret does not leave the device.
 *
 * @param context any context of the app; only its files directory is used.
 */
fun androidPairingStorePath(context: Context): String =
    File(File(context.filesDir, "datastore"), PAIRING_STORE_FILE_NAME).absolutePath
