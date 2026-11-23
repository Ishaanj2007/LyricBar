package com.example.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverlayFontCachingTest {

    private val unsafeInstance: Any? by lazy {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            theUnsafeField.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    private val allocateInstanceMethod: java.lang.reflect.Method? by lazy {
        unsafeInstance?.let { instance ->
            instance.javaClass.getMethod("allocateInstance", Class::class.java)
        }
    }

    private fun createSyntheticTypeface(): android.graphics.Typeface? {
        val method = allocateInstanceMethod
        val instance = unsafeInstance
        return if (method != null && instance != null) {
            method.invoke(instance, android.graphics.Typeface::class.java) as? android.graphics.Typeface
        } else {
            null
        }
    }

    @Before
    fun setUp() {
        MinimalOverlayView.clearTypefaceCache()
    }

    @After
    fun tearDown() {
        MinimalOverlayView.clearTypefaceCache()
    }

    @Test
    fun testTypefaceCacheLifecycleAndReusability() {
        assertEquals(0, MinimalOverlayView.cachedTypefaceCount())
        assertFalse(MinimalOverlayView.isTypefaceCached(1001))

        val tf1 = createSyntheticTypeface()
        if (tf1 == null) {
            // Unsafe not available in runtime; skip
            return
        }

        // Put in cache and verify
        MinimalOverlayView.putCachedTypefaceForTesting(1001, tf1)
        assertTrue(MinimalOverlayView.isTypefaceCached(1001))
        assertEquals(1, MinimalOverlayView.cachedTypefaceCount())

        val tf2 = createSyntheticTypeface() ?: return
        MinimalOverlayView.putCachedTypefaceForTesting(1002, tf2)
        assertTrue(MinimalOverlayView.isTypefaceCached(1002))
        assertEquals(2, MinimalOverlayView.cachedTypefaceCount())

        // Clear cache and verify all entries evicted
        MinimalOverlayView.clearTypefaceCache()
        assertEquals(0, MinimalOverlayView.cachedTypefaceCount())
        assertFalse(MinimalOverlayView.isTypefaceCached(1001))
        assertFalse(MinimalOverlayView.isTypefaceCached(1002))
    }
}
