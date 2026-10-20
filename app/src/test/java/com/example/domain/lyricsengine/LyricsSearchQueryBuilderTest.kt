package com.example.domain.lyricsengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSearchQueryBuilderTest {

    @Test
    fun testBuildQueryNormalSongLrc() {
        val query = LyricsSearchQueryBuilder.buildQuery("Cry", "Cigarettes After Sex", LyricsFileFormat.LRC)
        assertEquals("Cry Cigarettes After Sex LRC lyrics file download", query)
    }

    @Test
    fun testBuildQueryTtmlFormat() {
        val query = LyricsSearchQueryBuilder.buildQuery("First Love", "Hikaru Utada", LyricsFileFormat.TTML)
        assertEquals("First Love Hikaru Utada TTML lyrics file download", query)
    }

    @Test
    fun testBuildQuerySrtFormat() {
        val query = LyricsSearchQueryBuilder.buildQuery("Bohemian Rhapsody", "Queen", LyricsFileFormat.SRT)
        assertEquals("Bohemian Rhapsody Queen SRT lyrics file download", query)
    }

    @Test
    fun testBuildQueryTxtFormat() {
        val query = LyricsSearchQueryBuilder.buildQuery("Hotel California", "Eagles", LyricsFileFormat.TXT)
        assertEquals("Hotel California Eagles TXT lyrics", query)
    }

    @Test
    fun testBuildQueryCleansPlaceholderArtist() {
        val query = LyricsSearchQueryBuilder.buildQuery("Shape of You", "Unknown Artist", LyricsFileFormat.LRC)
        assertEquals("Shape of You LRC lyrics file download", query)
    }

    @Test
    fun testBuildQuerySpecialCharacters() {
        val query = LyricsSearchQueryBuilder.buildQuery("Rock & Roll", "AC/DC", LyricsFileFormat.LRC)
        assertEquals("Rock & Roll AC/DC LRC lyrics file download", query)
    }

    @Test
    fun testBuildGoogleSearchUrlEncoding() {
        val query = "Cry Cigarettes After Sex LRC lyrics file download"
        val url = LyricsSearchQueryBuilder.buildGoogleSearchUrl(query)
        assertEquals("https://www.google.com/search?q=Cry+Cigarettes+After+Sex+LRC+lyrics+file+download", url)
    }

    @Test
    fun testBuildGoogleSearchUrlWithSpecialCharacters() {
        val query = "Rock & Roll AC/DC LRC lyrics file download"
        val url = LyricsSearchQueryBuilder.buildGoogleSearchUrl(query)
        assertTrue(url.contains("Rock+%26+Roll"))
        assertTrue(url.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun testBuildGoogleSearchUrlWithJapaneseText() {
        val query = "First Love 宇多田ヒカル TTML lyrics file download"
        val url = LyricsSearchQueryBuilder.buildGoogleSearchUrl(query)
        assertTrue(url.startsWith("https://www.google.com/search?q="))
    }
}
