package com.neko.secretary.live2d

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEntryGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `rejects zip-slip traversal and absolute paths`() {
        val root = tempFolder.newFolder("model")
        assertNull(ZipEntryGuard.safeResolve(root, "../evil.txt"))
        assertNull(ZipEntryGuard.safeResolve(root, "sub/../../evil.txt"))
        assertNull(ZipEntryGuard.safeResolve(root, "/etc/passwd"))
        assertNull(ZipEntryGuard.safeResolve(root, "C:\\Windows\\system32\\cmd.exe"))
        assertNull(ZipEntryGuard.safeResolve(root, ""))
    }

    @Test
    fun `accepts safe relative model paths`() {
        val root = tempFolder.newFolder("model")
        val resolved = ZipEntryGuard.safeResolve(root, "neko/neko.model3.json")
        assertNotNull(resolved)
        assertTrue(resolved!!.path.startsWith(root.canonicalPath))
    }

    @Test
    fun `extractZip extracts valid pack and enforces size limit`() {
        val bytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("neko/neko.model3.json"))
                zos.write("""{"Version":3}""".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val dest = File(tempFolder.root, "extracted")
        Live2DModelRepository.extractZip(ByteArrayInputStream(bytes), dest, maxTotalBytes = 1024)
        assertTrue(File(dest, "neko/neko.model3.json").isFile)

        var threw = false
        try {
            Live2DModelRepository.extractZip(ByteArrayInputStream(bytes), File(tempFolder.root, "too-big"), maxTotalBytes = 4)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected size limit to throw", threw)
    }
}
