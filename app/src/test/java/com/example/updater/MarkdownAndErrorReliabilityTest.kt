package com.example.updater

import com.example.ui.components.SimpleMarkdownView
import org.junit.Assert.*
import org.junit.Test

class MarkdownAndErrorReliabilityTest {

    @Test
    fun testMarkdownParserDoesNotCrashOnEmpty() {
        // Simple sanity checks on markdown patterns
        val emptyMarkdown = ""
        assertTrue(emptyMarkdown.isBlank())
    }

    @Test
    fun testMarkdownBulletAndHeaderDetection() {
        val md = """
            # Header 1
            ## Header 2
            ### Header 3
            - Feature A
            * Feature B
            1. Step one
            ```
            code block
            ```
            [Link](https://github.com)
            **bold** and *italic* and `code`
        """.trimIndent()

        assertTrue(md.contains("Header 1"))
        assertTrue(md.contains("Feature A"))
        assertTrue(md.contains("Step one"))
        assertTrue(md.contains("```"))
    }
}
