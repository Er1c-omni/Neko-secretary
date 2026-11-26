package com.neko.secretary.live2d

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Resolves and imports user-supplied Live2D model packs (never shipped in Git / APK by default).
 *
 * Resolution order:
 * 1. filesDir/live2d/active/... (imported via the console "Import .zip" button)
 * 2. assets/live2d/<pack>/... (local developer testing, gitignored)
 */
object Live2DModelRepository {
    private const val TAG = "Live2DModelRepository"
    private const val MODEL_SUFFIX = ".model3.json"
    private const val MAX_SCAN_DEPTH = 4

    fun activeDir(context: Context): File = File(context.filesDir, "live2d/active")

    fun resolveActiveModel(context: Context): Live2DModelSource? =
        findImported(context) ?: findInAssets(context)

    private fun findImported(context: Context): Live2DModelSource? {
        val root = activeDir(context)
        if (!root.isDirectory) return null
        val json = findModelJson(root, 0) ?: return null
        return FileModelSource(rootDir = json.parentFile!!, modelJson = json.name)
    }

    private fun findModelJson(dir: File, depth: Int): File? {
        if (depth > MAX_SCAN_DEPTH) return null
        val children = dir.listFiles()?.sortedBy { it.name } ?: return null
        children.firstOrNull { it.isFile && it.name.endsWith(MODEL_SUFFIX) }?.let { return it }
        for (child in children) if (child.isDirectory) findModelJson(child, depth + 1)?.let { return it }
        return null
    }

    private fun findInAssets(context: Context): Live2DModelSource? = runCatching {
        val packs = context.assets.list("live2d").orEmpty()
        for (pack in packs.sorted()) {
            val files = context.assets.list("live2d/$pack").orEmpty()
            val json = files.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: continue
            return AssetModelSource(assetDir = "live2d/$pack", modelJson = json)
        }
        null
    }.getOrNull()

    /**
     * Import a .zip model pack picked through the Storage Access Framework.
     * Extracts into a staging dir with zip-slip and size guards, validates a *.model3.json exists,
     * then atomically swaps it in as the active pack.
     */
    fun importZip(context: Context, uri: Uri): Result<Live2DModelSource> = runCatching {
        val base = File(context.filesDir, "live2d")
        val staging = File(base, "staging-${System.currentTimeMillis()}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
            input.use { extractZip(it, staging) }
            val json = findModelJson(staging, 0) ?: error("zip does not contain a *$MODEL_SUFFIX")
            val active = activeDir(context)
            val trash = File(base, "trash-${System.currentTimeMillis()}")
            if (active.exists() && !active.renameTo(trash)) active.deleteRecursively()
            check(staging.renameTo(active)) { "failed to activate model pack" }
            trash.deleteRecursively()
            val relative = json.relativeTo(staging)
            val activeJson = File(active, relative.path)
            FileModelSource(rootDir = activeJson.parentFile!!, modelJson = activeJson.name)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            Log.w(TAG, "model import failed", t)
            throw t
        }
    }

    internal fun extractZip(input: InputStream, targetDir: File, maxTotalBytes: Long = ZipEntryGuard.MAX_TOTAL_BYTES) {
        targetDir.mkdirs()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = ZipEntryGuard.safeResolve(targetDir, entry.name)
                    ?: error("unsafe zip entry rejected: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= maxTotalBytes) { "model pack exceeds ${maxTotalBytes / (1024 * 1024)} MB" }
                            os.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}

/** Zip-slip guard, kept pure for JVM tests. */
object ZipEntryGuard {
    const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** @return the canonical destination file, or null if the entry escapes [root] or is absolute. */
    fun safeResolve(root: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains('\u0000')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, normalized).canonicalFile
        val rootPath = rootCanonical.path + File.separator
        return if (target.path.startsWith(rootPath) || target == rootCanonical) target else null
    }
}
