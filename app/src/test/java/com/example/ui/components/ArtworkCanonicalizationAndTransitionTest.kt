package com.example.ui.components

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
class ArtworkCanonicalizationAndTransitionTest {

    private val bitmapTags: MutableMap<Bitmap, Int> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Bitmap, Int>())

    @Before
    fun setUp() {
        ArtworkCanonicalizer.clear()
        ArtworkMemoryCache.clear()
        bitmapTags.clear()
        bitmapEqualityChecker = { a, b ->
            if (a === b) true
            else if (a == null || b == null) false
            else {
                val tagA = bitmapTags[a]
                val tagB = bitmapTags[b]
                tagA != null && tagA == tagB
            }
        }
    }

    @After
    fun tearDown() {
        ArtworkCanonicalizer.clear()
        ArtworkMemoryCache.clear()
        bitmapTags.clear()
    }

    private val allocateInstanceMethod: java.lang.reflect.Method? by lazy {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            unsafeInstance = theUnsafeField.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java)
        } catch (_: Throwable) {
            null
        }
    }
    private var unsafeInstance: Any? = null

    @Synchronized
    private fun createSolidBitmap(color: Int = 0, width: Int = 100, height: Int = 100): Bitmap {
        val method = allocateInstanceMethod
        val instance = unsafeInstance
        val bmp = if (method != null && instance != null) {
            method.invoke(instance, Bitmap::class.java) as Bitmap
        } else {
            val constructor = Bitmap::class.java.declaredConstructors.first().apply { isAccessible = true }
            val params = constructor.parameterTypes.map { clazz ->
                when {
                    clazz == java.lang.Long.TYPE -> 0L
                    clazz == java.lang.Integer.TYPE -> 0
                    clazz == java.lang.Boolean.TYPE -> false
                    clazz == java.lang.Byte.TYPE -> 0.toByte()
                    clazz.isArray -> java.lang.reflect.Array.newInstance(clazz.componentType!!, 0)
                    else -> null
                }
            }.toTypedArray()
            constructor.newInstance(*params) as Bitmap
        }
        bitmapTags[bmp] = color
        return bmp
    }

    @Test
    fun `same artwork with newly allocated Bitmap instances returns identical canonical reference and identity`() {
        val bmp1 = createSolidBitmap(0xFF0000)
        val bmp2 = createSolidBitmap(0xFF0000)
        assertFalse("Bitmaps must be distinct allocations", bmp1 === bmp2)

        val (canonical1, identity1) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp1)
        val (canonical2, identity2) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp2)

        assertSame("Canonical reference must be reused for identical pixels", bmp1, canonical1)
        assertSame("Subsequent calls must return canonical reference", bmp1, canonical2)
        assertEquals("Artwork identity must be unchanged across re-allocations", identity1, identity2)
    }

    @Test
    fun `different artwork with identical dimensions and config allocates fresh revision`() {
        val redBmp = createSolidBitmap(0xFF0000, 100, 100)
        val blueBmp = createSolidBitmap(0x0000FF, 100, 100)

        val (can1, id1) = ArtworkCanonicalizer.canonicalize("trackA", "", redBmp)
        val (can2, id2) = ArtworkCanonicalizer.canonicalize("trackA", "", blueBmp)

        assertSame(redBmp, can1)
        assertSame(blueBmp, can2)
        assertNotEquals("Different pixels must allocate a fresh revision even with matching dimensions", id1, id2)
    }

    @Test
    fun `track switching sequence A1 to B to A2 allocates unique revision and prevents collision`() {
        val bmpA1 = createSolidBitmap(0xFF0000)
        val bmpB = createSolidBitmap(0x00FF00)
        val bmpA2 = createSolidBitmap(0x0000FF)

        val (_, idA1) = ArtworkCanonicalizer.canonicalize("trackA", "", bmpA1)
        val (_, idB) = ArtworkCanonicalizer.canonicalize("trackB", "", bmpB)
        val (_, idA2) = ArtworkCanonicalizer.canonicalize("trackA", "", bmpA2)

        assertNotEquals("Track B must have different identity", idA1, idB)
        assertNotEquals("Track A2 must have unique revision different from A1", idA1, idA2)
        assertFalse("A2 identity must not collide with A1", idA2 == idA1)
    }

    @Test
    fun `LRU eviction allocates a fresh revision on track return and preserves cache correctness`() {
        val bmp = createSolidBitmap(0xFF0000)
        val (_, initialId) = ArtworkCanonicalizer.canonicalize("track_0", "", bmp)

        // Fill canonicalizer beyond the 16-entry LRU capacity to evict track_0
        for (i in 1..20) {
            val b = createSolidBitmap(i)
            ArtworkCanonicalizer.canonicalize("track_$i", "", b)
        }

        // Return to track_0
        val (_, returnedId) = ArtworkCanonicalizer.canonicalize("track_0", "", bmp)

        assertNotEquals("Evicted track must receive fresh globally unique revision on return", initialId, returnedId)
    }

    @Test
    fun `artwork URI change allocates fresh revision even for same track and bitmap`() {
        val bmp = createSolidBitmap(0xFF00FF)
        val (_, id1) = ArtworkCanonicalizer.canonicalize("trackA", "https://cdn.example.com/art_small.jpg", bmp)
        val (_, id2) = ArtworkCanonicalizer.canonicalize("trackA", "https://cdn.example.com/art_large.jpg", bmp)

        assertNotEquals("Changed URI must increment revision to load fresh artwork", id1, id2)
    }

    @Test
    fun `title-only metadata updates do not alter artwork identity`() {
        val bmp = createSolidBitmap(0xFFFF00)
        val (_, id1) = ArtworkCanonicalizer.canonicalize("Queen - Bohemian Rhapsody", "", bmp)
        val (_, id2) = ArtworkCanonicalizer.canonicalize("Queen - Bohemian Rhapsody", "", bmp)

        assertEquals("Same track key and artwork must produce identical identity", id1, id2)
    }

    @Test
    fun `repeated pause play and seek callbacks preserve stable canonical reference`() {
        val originalBmp = createSolidBitmap(0x00FFFF)
        val (_, baseId) = ArtworkCanonicalizer.canonicalize("trackA", "", originalBmp)

        for (i in 1..25) {
            val newAllocatedBmp = createSolidBitmap(0x00FFFF)
            val (canonical, id) = ArtworkCanonicalizer.canonicalize("trackA", "", newAllocatedBmp)
            assertSame("Must continuously retain original canonical reference", originalBmp, canonical)
            assertEquals("Must continuously retain stable identity", baseId, id)
        }
    }

    @Test
    fun `concurrent requests from MainActivity and NowPlayingScreen return identical reference and identity`() = runBlocking {
        val bmp = createSolidBitmap(0x000000)
        val results = (1..10).map {
            async(Dispatchers.Default) {
                val newAlloc = createSolidBitmap(0x000000)
                ArtworkCanonicalizer.canonicalize("trackConcurrent", "", newAlloc)
            }
        }.awaitAll()

        val firstCanonical = results.first().first
        val firstId = results.first().second

        for (res in results) {
            assertSame("All concurrent callers must receive identical canonical bitmap", firstCanonical, res.first)
            assertEquals("All concurrent callers must receive identical artwork identity", firstId, res.second)
        }
    }

    @Test
    fun `clear invalidates in-flight asynchronous operations and purges cache`() {
        val bmp = createSolidBitmap(0xFF0000)
        val (_, id1) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp)

        val blurBmp = createSolidBitmap(0x808080, 50, 50)
        ArtworkMemoryCache.put("blur_$id1", blurBmp)
        assertNotNull("Blur bitmap must be in cache", ArtworkMemoryCache.get("blur_$id1"))

        ArtworkCanonicalizer.clear()

        assertNull("Cache must be emptied by clear()", ArtworkMemoryCache.get("blur_$id1"))

        val (_, idAfterClear) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp)
        assertNotEquals("New revision must be allocated after clear()", id1, idAfterClear)
    }

    @Test
    fun `glow and blur cache entries from superseded revision are evicted on artwork update`() {
        val bmp1 = createSolidBitmap(0xFF0000)
        val (_, id1) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp1)

        val blurBmp = createSolidBitmap(0x333333, 50, 50)
        val glowBmp = createSolidBitmap(0xCCCCCC, 50, 50)
        ArtworkMemoryCache.put("blur_$id1", blurBmp)
        ArtworkMemoryCache.put("glow_${id1}_palette123", glowBmp)

        assertNotNull(ArtworkMemoryCache.get("blur_$id1"))
        assertNotNull(ArtworkMemoryCache.get("glow_${id1}_palette123"))

        // Update to genuinely different artwork
        val bmp2 = createSolidBitmap(0x0000FF)
        val (_, id2) = ArtworkCanonicalizer.canonicalize("trackA", "", bmp2)

        assertNull("Old blur entry must be evicted on new revision", ArtworkMemoryCache.get("blur_$id1"))
        assertNull("Old glow entries must be evicted on new revision", ArtworkMemoryCache.get("glow_${id1}_palette123"))
        assertNotEquals(id1, id2)
    }

    @Test
    fun `rapid track switching maintains separate blur identities and isolates missing artwork`() {
        val bmpA = createSolidBitmap(0x111111)
        val bmpB = createSolidBitmap(0x222222)

        val (_, idA) = ArtworkCanonicalizer.canonicalize("trackA", "", bmpA)
        val (_, idB) = ArtworkCanonicalizer.canonicalize("trackB", "", bmpB)

        val blurA = createSolidBitmap(0x333333, 32, 32)
        val blurB = createSolidBitmap(0x444444, 32, 32)

        ArtworkMemoryCache.put("blur_$idA", blurA)
        ArtworkMemoryCache.put("blur_$idB", blurB)

        assertSame("Track A blur must match its own cached bitmap", blurA, ArtworkMemoryCache.get("blur_$idA"))
        assertSame("Track B blur must match its own cached bitmap", blurB, ArtworkMemoryCache.get("blur_$idB"))
        assertNotEquals("Blur cache keys must never collide across distinct tracks", "blur_$idA", "blur_$idB")

        // Track C has no artwork: identity must be trackC::none and not match any cached blur
        val idC = ArtworkCanonicalizer.getArtworkIdentity("trackC", null)
        assertEquals("trackC::none", idC)
        assertNull("Track with no artwork must have null blur cache entry", ArtworkMemoryCache.get("blur_$idC"))

        // Blank track key returns idle_sunset
        val idIdle = ArtworkCanonicalizer.getArtworkIdentity("", null)
        assertEquals("idle_sunset", idIdle)
    }

    @Test
    fun `artwork identity updates correctly when artwork bitmap arrives after metadata`() {
        // Initial track arrival without artwork
        val identityNoArt = ArtworkCanonicalizer.getArtworkIdentity("trackDelayed", null)
        assertEquals("trackDelayed::none", identityNoArt)

        // Asynchronous artwork arrival
        val bmpDelayed = createSolidBitmap(0x555555)
        val identityWithArt = ArtworkCanonicalizer.getArtworkIdentity("trackDelayed", bmpDelayed)

        assertTrue("Artwork identity must transition from none to revisioned identity", identityWithArt.startsWith("trackDelayed::r"))
        assertNotEquals(identityNoArt, identityWithArt)

        // Same bitmap delivered again must preserve stable revision identity
        val identityRepeat = ArtworkCanonicalizer.getArtworkIdentity("trackDelayed", bmpDelayed)
        assertEquals("Subsequent deliveries of the identical bitmap must retain stable identity", identityWithArt, identityRepeat)
    }
}
