package com.mklhc.core.data.identity

import com.mklhc.core.data.MiniJson
import com.mklhc.core.data.readTestResource

/**
 * Typed view of `identity_vectors.json`, the golden vectors produced by `tools/gen_identity_vectors.py`
 * from the real OpenKart implementation (openkartd/config.py, `Config.process` and its `derive()`).
 *
 * Each vector gives a master secret and the byte-exact outputs of the reference derivation. The SSID
 * is recorded as its base64 tail only: the reference prepends its own brand prefix, which this app
 * deliberately replaces.
 */
internal class IdentityVectors private constructor(root: Map<String, Any?>) {

    class Vector(
        val name: String,
        val secret: ByteArray,
        val secretText: String?,
        val ssidRandom: ByteArray,
        val ssidTailBase64: String,
        val psk: ByteArray,
        val rcdIdent: ByteArray,
        val rcdMasterKey: ByteArray,
    )

    val vectors: List<Vector> = root.list("vectors").map { v ->
        val o = v.obj()
        Vector(
            name = o.string("name"),
            secret = o.bytes("secret_hex"),
            secretText = o["secret_text"] as String?,
            ssidRandom = o.bytes("ssid_random_hex"),
            ssidTailBase64 = o.string("ssid_tail_b64"),
            psk = o.bytes("psk"),
            rcdIdent = o.bytes("rcd_ident"),
            rcdMasterKey = o.bytes("rcd_master_key"),
        )
    }

    companion object {
        private const val RESOURCE_NAME = "identity_vectors.json"

        /** Loads and parses the vectors file from the test classpath. */
        fun load(): IdentityVectors = IdentityVectors(MiniJson.parse(readTestResource(RESOURCE_NAME)).obj())
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

private fun Map<String, Any?>.string(key: String): String = this[key] as String

private fun Map<String, Any?>.bytes(key: String): ByteArray = string(key).hexToByteArray()
