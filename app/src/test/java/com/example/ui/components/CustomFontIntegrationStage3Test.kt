package com.example.ui.components

import com.example.data.preferences.FontOption
import com.example.ui.theme.clearCustomFontFamilyCache
import com.example.ui.theme.getFontFamily
import com.example.ui.theme.resolveLyricsFontFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomFontIntegrationStage3Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        MinimalOverlayView.clearTypefaceCache()
        clearCustomFontFamilyCache()
    }

    @Test
    fun `missing or corrupted custom font files fall back cleanly without crashing`() {
        val missingPath = File(tempFolder.root, "does_not_exist.ttf").absolutePath

        // Overlay Typeface cache handles missing/blank/corrupted paths gracefully
        assertNull(MinimalOverlayView.getCachedFileTypeface(""))
        assertNull(MinimalOverlayView.getCachedFileTypeface(missingPath))
        assertNull(MinimalOverlayView.getCachedBoldFileTypeface(missingPath))

        val corrupted = tempFolder.newFile("corrupt.ttf").apply {
            writeText("not a font binary")
        }
        assertNull(MinimalOverlayView.getCachedFileTypeface(corrupted.absolutePath))
        MinimalOverlayView.evictFileTypefaceCache(corrupted.absolutePath)

        // Compose FontFamily resolver falls back cleanly to built-in FontOption when missing
        assertEquals(
            getFontFamily(FontOption.POPPINS),
            resolveLyricsFontFamily(FontOption.POPPINS, missingPath)
        )
        assertEquals(
            getFontFamily(FontOption.INTER),
            resolveLyricsFontFamily(FontOption.INTER, null)
        )
    }

    @Test
    fun `valid custom font file resolves distinct Compose FontFamily and caches cleanly`() {
        val bundledSample = File("src/main/res/font/inter.ttf")
        val copiedFont = tempFolder.newFile("CustomTestInter.ttf").apply {
            if (bundledSample.isFile) {
                writeBytes(bundledSample.readBytes())
            } else {
                writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x0A, 0x41, 0x42))
            }
        }
        val resolved = resolveLyricsFontFamily(FontOption.POPPINS, copiedFont.absolutePath)
        val cachedAgain = resolveLyricsFontFamily(FontOption.POPPINS, copiedFont.absolutePath)
        assertEquals(cachedAgain, resolved)
        assertTrue(copiedFont.isFile && copiedFont.length() > 0L)

        clearCustomFontFamilyCache()
        assertEquals(0, MinimalOverlayView.cachedTypefaceCount())
    }
}
