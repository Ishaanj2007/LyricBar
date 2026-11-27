package com.example.data.fonts

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.data.preferences.FontOption
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class CustomFontRepositoryStage1Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createPrefsRepo(name: String): UserPreferencesRepository {
        val dir = tempFolder.newFolder("ds_$name")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "$name.preferences_pb") }
        )
        return UserPreferencesRepository(dataStore)
    }

    private fun sampleTtfBytes(tagByte: Byte = 0x41): ByteArray {
        return byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x0A, tagByte, 0x42)
    }

    private fun sampleOtfBytes(tagByte: Byte = 0x43): ByteArray {
        return byteArrayOf(0x4F, 0x54, 0x54, 0x4F, 0x00, 0x0A, tagByte, 0x44)
    }

    @Test
    fun `imports valid ttf and otf fonts, persists registry, and selects custom font`() = runBlocking {
        val fontsDir = tempFolder.newFolder("fonts1")
        val tmpDir = tempFolder.newFolder("tmp1")
        val prefs = createPrefsRepo("fonts_persist")

        val repo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = prefs,
            typefaceVerifier = { true }
        )

        val ttfRes = repo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes()), "Devanagari_Sans-Regular.ttf")
        assertTrue(ttfRes is SingleFontImportResult.Success)
        val ttfRecord = (ttfRes as SingleFontImportResult.Success).record
        assertEquals("ttf", ttfRecord.extension)
        assertEquals("Devanagari Sans Regular", ttfRecord.displayName)
        assertTrue(File(fontsDir, ttfRecord.storedFileName).isFile)

        val otfRes = repo.importFontFromStream(ByteArrayInputStream(sampleOtfBytes()), "Editorial.otf")
        assertTrue(otfRes is SingleFontImportResult.Success)
        val otfRecord = (otfRes as SingleFontImportResult.Success).record
        assertEquals("otf", otfRecord.extension)

        prefs.setSelectedCustomFontId(ttfRecord.id)
        val loadedPrefs = prefs.userPreferencesFlow.first()
        assertEquals(ttfRecord.id, loadedPrefs.selectedCustomFontId)
        assertEquals(FontOption.POPPINS, loadedPrefs.selectedFont)

        // Simulate app restart with fresh repository instance against same storage
        val restartedRepo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = prefs,
            typefaceVerifier = { true }
        )
        assertEquals(2, restartedRepo.customFontsFlow.value.size)
        assertNotNull(restartedRepo.resolveValidFontFile(ttfRecord.id))
        assertEquals(0, tmpDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `rejects invalid extensions, empty files, fake renamed files, corrupted fonts, and oversized fonts`() = runBlocking {
        val fontsDir = tempFolder.newFolder("fonts2")
        val tmpDir = tempFolder.newFolder("tmp2")
        val prefs = createPrefsRepo("fonts_validation")

        val fakeResRepo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = prefs,
            typefaceVerifier = { true }
        )

        val badExt = fakeResRepo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes()), "notes.txt")
        assertEquals(FontImportErrorCode.UNSUPPORTED_EXTENSION, (badExt as SingleFontImportResult.Failure).errorCode)

        val emptyFile = fakeResRepo.importFontFromStream(ByteArrayInputStream(ByteArray(0)), "empty.ttf")
        assertEquals(FontImportErrorCode.EMPTY_FILE, (emptyFile as SingleFontImportResult.Failure).errorCode)

        val renamedFake = fakeResRepo.importFontFromStream(ByteArrayInputStream("hello world".toByteArray()), "fake.ttf")
        assertEquals(FontImportErrorCode.CORRUPTED_OR_INVALID_FONT, (renamedFake as SingleFontImportResult.Failure).errorCode)

        val tooBig = fakeResRepo.importFontFromStream(
            inputStream = ByteArrayInputStream(sampleTtfBytes()),
            originalFileName = "big.ttf",
            maxFontBytes = 4L
        )
        assertEquals(FontImportErrorCode.FILE_TOO_LARGE, (tooBig as SingleFontImportResult.Failure).errorCode)

        // Valid SFNT header, but Android Typeface creation fails (corrupted glyph tables)
        val rejectingRepo = CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = prefs,
            typefaceVerifier = { false }
        )
        val corrupted = rejectingRepo.importFontFromStream(ByteArrayInputStream(sampleOtfBytes()), "broken.otf")
        assertEquals(FontImportErrorCode.CORRUPTED_OR_INVALID_FONT, (corrupted as SingleFontImportResult.Failure).errorCode)

        assertTrue(fakeResRepo.refreshRegistry().isEmpty())
        assertEquals(0, tmpDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `detects duplicate font content by sha256 even with different filenames`() = runBlocking {
        val fontsDir = tempFolder.newFolder("fonts3")
        val tmpDir = tempFolder.newFolder("tmp3")
        val prefs = createPrefsRepo("fonts_dup")
        val repo = CustomFontRepository(fontsDir, tmpDir, prefs, typefaceVerifier = { true })

        val payload = sampleTtfBytes(0x55)
        val first = repo.importFontFromStream(ByteArrayInputStream(payload), "OriginalFont.ttf")
        assertTrue(first is SingleFontImportResult.Success)

        val second = repo.importFontFromStream(ByteArrayInputStream(payload), "RenamedCopy.ttf")
        assertTrue(second is SingleFontImportResult.Duplicate)
        assertEquals((first as SingleFontImportResult.Success).record.id, (second as SingleFontImportResult.Duplicate).existingRecord.id)
        assertEquals(1, repo.customFontsFlow.value.size)
    }

    @Test
    fun `deleting selected custom font falls back to built-in Poppins and deletes file`() = runBlocking {
        val fontsDir = tempFolder.newFolder("fonts4")
        val tmpDir = tempFolder.newFolder("tmp4")
        val prefs = createPrefsRepo("fonts_delete")
        val repo = CustomFontRepository(fontsDir, tmpDir, prefs, typefaceVerifier = { true })

        val f1 = (repo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x11)), "First.ttf") as SingleFontImportResult.Success).record
        val f2 = (repo.importFontFromStream(ByteArrayInputStream(sampleOtfBytes(0x22)), "Second.otf") as SingleFontImportResult.Success).record

        prefs.setSelectedCustomFontId(f2.id)

        // Delete unselected font: keeps active custom selection intact
        val delUnselected = repo.deleteCustomFont(f1.id)
        assertTrue(delUnselected is CustomFontDeleteResult.Deleted)
        assertFalse((delUnselected as CustomFontDeleteResult.Deleted).resetSelectionToDefault)
        assertEquals(f2.id, prefs.userPreferencesFlow.first().selectedCustomFontId)
        assertFalse(File(fontsDir, f1.storedFileName).exists())

        // Delete currently selected font: resets custom selection cleanly to null (built-in Poppins)
        val delSelected = repo.deleteCustomFont(f2.id)
        assertTrue(delSelected is CustomFontDeleteResult.Deleted)
        assertTrue((delSelected as CustomFontDeleteResult.Deleted).resetSelectionToDefault)
        assertNull(prefs.userPreferencesFlow.first().selectedCustomFontId)
        assertEquals(FontOption.POPPINS, prefs.userPreferencesFlow.first().selectedFont)
        assertTrue(repo.customFontsFlow.value.isEmpty())
        assertFalse(File(fontsDir, f2.storedFileName).exists())
    }

    @Test
    fun `batch delete removes multiple selected fonts, supports deleting all, and resets active selection`() = runBlocking {
        val fontsDir = tempFolder.newFolder("fonts5")
        val tmpDir = tempFolder.newFolder("tmp5")
        val prefs = createPrefsRepo("fonts_batch_delete")
        val repo = CustomFontRepository(fontsDir, tmpDir, prefs, typefaceVerifier = { true })

        val f1 = (repo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x31)), "Alpha.ttf") as SingleFontImportResult.Success).record
        val f2 = (repo.importFontFromStream(ByteArrayInputStream(sampleOtfBytes(0x32)), "Beta.otf") as SingleFontImportResult.Success).record
        val f3 = (repo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x33)), "Gamma.ttf") as SingleFontImportResult.Success).record

        prefs.setSelectedCustomFontId(f3.id)

        // Multi-select partial delete (f1 + f2): f3 remains selected
        val partial = repo.deleteCustomFonts(listOf(f1.id, f2.id, "missing_id"))
        assertEquals(listOf(f1.id, f2.id), partial.deletedRecords.map { it.id })
        assertEquals(listOf("missing_id"), partial.notFoundIds)
        assertFalse(partial.resetSelectionToDefault)
        assertEquals(listOf(f3.id), repo.customFontsFlow.value.map { it.id })
        assertEquals(f3.id, prefs.userPreferencesFlow.first().selectedCustomFontId)
        assertFalse(File(fontsDir, f1.storedFileName).exists())
        assertFalse(File(fontsDir, f2.storedFileName).exists())
        assertTrue(File(fontsDir, f3.storedFileName).isFile)

        // Select-all remaining batch delete: resets active selection cleanly
        val deleteAll = repo.deleteCustomFonts(repo.customFontsFlow.value.map { it.id })
        assertEquals(listOf(f3.id), deleteAll.deletedRecords.map { it.id })
        assertTrue(deleteAll.notFoundIds.isEmpty())
        assertTrue(deleteAll.resetSelectionToDefault)
        assertTrue(repo.customFontsFlow.value.isEmpty())
        assertNull(prefs.userPreferencesFlow.first().selectedCustomFontId)
        assertFalse(File(fontsDir, f3.storedFileName).exists())
    }
}
