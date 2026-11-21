package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class ImageEncodeCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun initCache() {
        ImageEncodeCache.init(temp.root)
    }

    @Test
    fun `data uri file is cached and streamed back`() {
        val key = "test-key"
        ImageEncodeCache.putDataUri(key) { out ->
            out.write("data:image/jpeg;base64,YQ==".toByteArray())
        }
        val file = ImageEncodeCache.getDataUri(key)
        assertNotNull(file)
        assertEquals("data:image/jpeg;base64,YQ==", file!!.readText())
        assertTrue(file.length() > 0)
    }
}