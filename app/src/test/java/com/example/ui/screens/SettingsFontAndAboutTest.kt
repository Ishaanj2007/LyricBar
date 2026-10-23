package com.example.ui.screens

import com.example.data.preferences.FontOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFontAndAboutTest {

    @Test
    fun testFontOptionDefaultTopFour() {
        val allFonts = FontOption.entries
        val topFour = allFonts.take(4)
        assertEquals(4, topFour.size)
        assertEquals(FontOption.SYSTEM, topFour[0])
        assertEquals(FontOption.INTER, topFour[1])
        assertEquals(FontOption.POPPINS, topFour[2])
        assertEquals(FontOption.MONTSERRAT, topFour[3])
        assertEquals(13, allFonts.size)
    }

    @Test
    fun testDeveloperConfigConstants() {
        assertEquals("Ishaan", DeveloperConfig.DEVELOPER_NAME)
        assertEquals("https://github.com/ishaanj2007", DeveloperConfig.GITHUB_PROFILE_URL)
        assertEquals("https://github.com/ishaanj2007/LyricBar", DeveloperConfig.SOURCE_CODE_URL)
    }
}
