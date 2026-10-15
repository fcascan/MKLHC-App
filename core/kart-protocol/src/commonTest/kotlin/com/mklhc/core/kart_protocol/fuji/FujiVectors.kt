package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.MiniJson
import com.mklhc.core.kart_protocol.rcd.readTestResource

/**
 * Typed view of `fuji_vectors.json`, the golden vectors produced by `tools/gen_fuji_vectors.py` from
 * the real OpenKart implementation (openkartd/fuji.py) -- plus the ReadApplicationData layout of
 * mk-live, which OpenKart does not have.
 *
 * Every request vector carries the exact `(service, command, request)` the reference invoked, so the
 * Kotlin clients are asserted byte for byte, including the 0x80 / 0x10 / 0x20 zero padding and the
 * little-endian `<HHHHQ` connection-info packing.
 */
internal class FujiVectors private constructor(root: Map<String, Any?>) {

    /** The exact RCD request a client method must put on the wire. */
    class Call(val service: Int, val command: Int, val request: ByteArray)

    class Padding(val name: String, val input: ByteArray, val length: Int, val padded: ByteArray)

    class SystemInfo(
        val name: String,
        val input: ByteArray,
        val bootVersion: Pair<Int, Int>,
        val systemVersion: Pair<Int, Int>,
        val sha1: String,
    )

    class ProductCode(
        val name: String,
        val input: ByteArray,
        val unk1: Int,
        val character: Int,
        val unk2: Int,
        val serial: String,
    )

    class GetSystemInfo(
        val call: Call,
        val response: ByteArray,
        val bootVersion: Pair<Int, Int>,
        val systemVersion: Pair<Int, Int>,
        val sha1: String,
    )

    class SetParam(val name: String, val param: String, val value: ByteArray, val call: Call)

    class ConnectionInfo(
        val name: String,
        val telemetryPort: Int,
        val videoControlPort: Int,
        val videoStreamPort: Int,
        val unk: Int,
        val timestamp: Long,
        val call: Call,
    )

    class GetParam(
        val name: String,
        val param: String,
        val response: ByteArray,
        val result: ByteArray,
        val call: Call,
    )

    class ProductCodeFlow(val call: Call, val response: ByteArray, val code: ProductCode)

    class SetState(val state: Int, val call: Call)

    class ReadApplicationData(val name: String, val applicationId: String, val maxSize: Int, val call: Call)

    class SetGroupInfo(val name: String, val ssid: String, val psk: ByteArray, val call: Call)

    val controlServiceId: Int = root.obj("constants").int("control_service_id")
    val pairingServiceId: Int = root.obj("constants").int("pairing_service_id")
    val drivePort: Int = root.obj("constants").int("drive_port")
    val controlPort: Int = root.obj("constants").int("control_port")
    val pairingPort: Int = root.obj("constants").int("pairing_port")
    val eventPort: Int = root.obj("constants").int("event_port")
    val applicationId: String = root.obj("constants").string("application_id")

    val padding: List<Padding> = root.list("padding").map { v ->
        val o = v.obj()
        Padding(o.string("name"), o.bytes("input"), o.int("length"), o.bytes("padded"))
    }

    val systemInfo: List<SystemInfo> = root.list("system_info").map { v ->
        val o = v.obj()
        SystemInfo(
            name = o.string("name"),
            input = o.bytes("input"),
            bootVersion = o.pair("boot_version"),
            systemVersion = o.pair("system_version"),
            sha1 = o.codePointsString("sha1_codepoints"),
        )
    }

    val productCode: List<ProductCode> = root.list("product_code").map { v -> v.obj().toProductCode() }

    val getSystemInfo: GetSystemInfo = root.obj("get_system_info").let { o ->
        GetSystemInfo(
            call = o.toCall(),
            response = o.bytes("response"),
            bootVersion = o.pair("boot_version"),
            systemVersion = o.pair("system_version"),
            sha1 = o.string("sha1"),
        )
    }

    val setParam: List<SetParam> = root.list("set_param").map { v ->
        val o = v.obj()
        SetParam(o.string("name"), o.string("param"), o.bytes("value"), o.toCall())
    }

    val connectionInfo: List<ConnectionInfo> = root.list("connection_info").map { v ->
        val o = v.obj()
        ConnectionInfo(
            name = o.string("name"),
            telemetryPort = o.int("telemetry_port"),
            videoControlPort = o.int("video_control_port"),
            videoStreamPort = o.int("video_stream_port"),
            unk = o.int("unk"),
            // A 64-bit timestamp does not survive a JSON double, so the generator wrote a string.
            timestamp = o.string("timestamp").toLong(),
            call = o.toCall(),
        )
    }

    val getParam: List<GetParam> = root.list("get_param").map { v ->
        val o = v.obj()
        GetParam(o.string("name"), o.string("param"), o.bytes("response"), o.bytes("result"), o.toCall())
    }

    val productCodeFlow: ProductCodeFlow = root.obj("product_code_flow").let { o ->
        ProductCodeFlow(o.toCall(), o.bytes("response"), o.toProductCode(name = "product_code flow"))
    }

    val setState: List<SetState> = root.list("set_state").map { v ->
        val o = v.obj()
        SetState(o.int("state"), o.toCall())
    }

    val shutdown: Call = root.obj("shutdown").toCall()

    val readApplicationData: List<ReadApplicationData> = root.list("read_application_data").map { v ->
        val o = v.obj()
        ReadApplicationData(o.string("name"), o.string("application_id"), o.int("max_size"), o.toCall())
    }

    val setGroupInfo: List<SetGroupInfo> = root.list("set_group_info").map { v ->
        val o = v.obj()
        SetGroupInfo(o.string("name"), o.string("ssid"), o.bytes("psk"), o.toCall())
    }

    companion object {
        private const val RESOURCE_NAME = "fuji_vectors.json"

        /** Loads and parses the vectors file from the test classpath. */
        fun load(): FujiVectors = FujiVectors(MiniJson.parse(readTestResource(RESOURCE_NAME)).obj())
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

private fun Map<String, Any?>.string(key: String): String = this[key] as String

private fun Map<String, Any?>.int(key: String): Int = (this[key] as Double).toInt()

private fun Map<String, Any?>.bytes(key: String): ByteArray = string(key).hexToByteArray()

private fun Map<String, Any?>.pair(key: String): Pair<Int, Int> =
    list(key).let { (it[0] as Double).toInt() to (it[1] as Double).toInt() }

/** Rebuilds a latin1 string from code points, so non-ASCII text is asserted without JSON escapes. */
private fun Map<String, Any?>.codePointsString(key: String): String =
    list(key).map { (it as Double).toInt().toChar() }.joinToString("")

private fun Map<String, Any?>.toCall(): FujiVectors.Call =
    FujiVectors.Call(int("service"), int("command"), bytes("request"))

private fun Map<String, Any?>.toProductCode(name: String? = null): FujiVectors.ProductCode =
    FujiVectors.ProductCode(
        name = name ?: string("name"),
        input = if (containsKey("input")) bytes("input") else ByteArray(0),
        unk1 = int("unk1"),
        character = int("character"),
        unk2 = int("unk2"),
        serial = string("serial"),
    )
