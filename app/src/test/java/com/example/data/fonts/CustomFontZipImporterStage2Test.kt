package com.example.data.fonts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CustomFontZipImporterStage2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepo(
        typefaceVerifier: (File) -> Boolean = { true }
    ): Pair<CustomFontRepository, CustomFontZipImporter> {
        val fontsDir = tempFolder.newFolder("fonts_${System.nanoTime()}")
        val tmpDir = tempFolder.newFolder("tmp_${System.nanoTime()}")
        val dsDir = tempFolder.newFolder("ds_${System.nanoTime()}")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dsDir, "prefs.preferences_pb") }
        )
        val repo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = UserPreferencesRepository(dataStore),
            typefaceVerifier = typefaceVerifier
        )
        val importer = CustomFontZipImporter(
            repository = repo,
            stagingRootDir = tmpDir,
            typefaceVerifier = typefaceVerifier
        )
        return repo to importer
    }

    private fun sampleTtfBytes(tag: Byte = 0x41): ByteArray =
        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x0A, tag, 0x42)

    private fun sampleOtfBytes(tag: Byte = 0x43): ByteArray =
        byteArrayOf(0x4F, 0x54, 0x54, 0x4F, 0x00, 0x0A, tag, 0x44)

    private fun buildZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `discovers nested fonts, ignores unrelated and metadata entries, flags duplicates, and selectively imports`() = runBlocking {
        val (repo, importer) = createRepo()
        val zipBytes = buildZipBytes(
            listOf(
                "README.txt" to "license".toByteArray(),
                "nested/preview.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                "__MACOSX/._Ignored.ttf" to "appledouble".toByteArray(),
                "nested/archive.zip" to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
                "fonts/latin/MySans.ttf" to sampleTtfBytes(0x11),
                "fonts/devanagari/HindiSerif.otf" to sampleOtfBytes(0x22),
                "fonts/duplicate/MySansCopy.ttf" to sampleTtfBytes(0x11),
                "fonts/broken/Fake.ttf" to "not a real font".toByteArray()
            )
        )

        val scan = importer.scanZipStream(ByteArrayInputStream(zipBytes), "bundle.zip")
        assertTrue(scan is ZipFontScanResult.Success)
        val success = scan as ZipFontScanResult.Success
        assertEquals(4, success.discoveredFonts.size)

        val byName = success.discoveredFonts.associateBy { it.fileName }
        assertEquals(ZipFontValidationStatus.READY, byName["MySans.ttf"]?.status)
        assertEquals(ZipFontValidationStatus.READY, byName["HindiSerif.otf"]?.status)
        assertEquals(ZipFontValidationStatus.DUPLICATE_IN_ARCHIVE, byName["MySansCopy.ttf"]?.status)
        assertEquals(ZipFontValidationStatus.UNSUPPORTED_OR_CORRUPTED, byName["Fake.ttf"]?.status)
        assertFalse(byName["Fake.ttf"]?.isSelectable ?: true)

        // Selective import: user picks ONLY HindiSerif.otf
        val selectedOnly = listOf(byName.getValue("HindiSerif.otf"))
        val batch = importer.importSelectedEntries(success.sessionId, selectedOnly)
        assertEquals(1, batch.imported.size)
        assertEquals("HindiSerif", batch.imported.first().displayName)
        assertTrue(batch.failed.isEmpty())
        assertEquals(1, repo.customFontsFlow.value.size)

        // Staging session directory cleaned up
        val sessionDir = File(tempFolder.root.listFiles()!!.first { it.name.startsWith("tmp_") }, success.sessionId)
        assertFalse(sessionDir.exists())
    }

    @Test
    fun `rejects path traversal entries, empty font zips, corrupted zips, and oversized fonts`() = runBlocking {
        val (repo, importer) = createRepo()

        // Path traversal (ZIP Slip)
        val traversalZip = buildZipBytes(listOf("../../evil.ttf" to sampleTtfBytes()))
        val traversalRes = importer.scanZipStream(ByteArrayInputStream(traversalZip), "evil.zip")
        assertTrue(traversalRes is ZipFontScanResult.Failure)
        assertEquals(FontImportErrorCode.SECURITY_VIOLATION, (traversalRes as ZipFontScanResult.Failure).errorCode)

        // No supported fonts
        val noFontsZip = buildZipBytes(listOf("readme.md" to "hello".toByteArray(), "pic.jpg" to byteArrayOf(1, 2, 3)))
        val noFontsRes = importer.scanZipStream(ByteArrayInputStream(noFontsZip), "docs.zip")
        assertTrue(noFontsRes is ZipFontScanResult.NoFontsFound)

        // Corrupted ZIP bytes
        val corruptRes = importer.scanZipStream(ByteArrayInputStream("definitely not a zip".toByteArray()), "bad.zip")
        assertTrue(corruptRes is ZipFontScanResult.Failure)
        assertEquals(FontImportErrorCode.ARCHIVE_CORRUPTED, (corruptRes as ZipFontScanResult.Failure).errorCode)

        // Oversized single font inside ZIP while smaller valid font still succeeds
        val tmpDir = tempFolder.newFolder("tmp_limit")
        val fontsDir = tempFolder.newFolder("fonts_limit")
        val dsDir = tempFolder.newFolder("ds_limit")
        val smallLimitRepo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    produceFile = { File(dsDir, "prefs.preferences_pb") }
                )
            ),
            typefaceVerifier = { true }
        )
        val smallLimitImporter = CustomFontZipImporter(
            repository = smallLimitRepo,
            stagingRootDir = tmpDir,
            typefaceVerifier = { true },
            maxFontBytes = 12L
        )
        val mixedZip = buildZipBytes(
            listOf(
                "Small.ttf" to sampleTtfBytes(),
                "Huge.ttf" to (sampleTtfBytes() + ByteArray(20))
            )
        )
        val mixedScan = smallLimitImporter.scanZipStream(ByteArrayInputStream(mixedZip), "mixed.zip") as ZipFontScanResult.Success
        assertEquals(ZipFontValidationStatus.READY, mixedScan.discoveredFonts[0].status)
        assertEquals(ZipFontValidationStatus.TOO_LARGE, mixedScan.discoveredFonts[1].status)
        smallLimitImporter.cleanupSession(mixedScan.sessionId)
        assertEquals(0, repo.customFontsFlow.value.size)
    }
}
