package dev.ryanchhab.flux.gradle

import java.io.File
import java.security.MessageDigest

/** sha256 helpers shared by version stamping (BUILD_ID), the dex cache, and tier detection. */
object Hashing {

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String =
        sha256Hex(file.readBytes())

    /**
     * Deterministic content hash of every regular file under [root], sorted by relative path so
     * the result is stable regardless of filesystem iteration order.
     */
    fun sha256OfTree(root: File): String {
        if (!root.exists()) return sha256Hex(ByteArray(0))
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .forEach { file ->
                digest.update(file.relativeTo(root).path.toByteArray())
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** First [len] hex chars — used for `__FluxVersion.BUILD_ID` per CONTRACT.md. */
    fun shorten(hex: String, len: Int = 12): String = hex.take(len)
}
