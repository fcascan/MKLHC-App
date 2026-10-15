package com.mklhc.core.kart_protocol.rcd

/** Marker used only to reach the test class loader for resource lookups. */
private object TestResourceAnchor

/**
 * JVM actual of [readTestResource]: loads the resource through the class loader that holds the
 * test classes, which is where Gradle places `src/commonTest/resources`.
 */
internal actual fun readTestResource(name: String): String {
    val stream = TestResourceAnchor::class.java.getResourceAsStream("/$name")
        ?: error("Test resource '$name' not found on the test classpath")
    return stream.use { String(it.readBytes(), Charsets.UTF_8) }
}
