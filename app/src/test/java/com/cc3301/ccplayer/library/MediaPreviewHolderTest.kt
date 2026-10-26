package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MediaPreviewHolderTest {

    @Test
    fun `首次取用才创建`() {
        var created = 0
        val holder = MediaPreviewHolder {
            created++
            FakePreviewSource()
        }

        assertEquals(0, created)

        holder.instance

        assertEquals(1, created)
    }

    @Test
    fun `多次取用复用同一个预览源`() {
        var created = 0
        val holder = MediaPreviewHolder {
            created++
            FakePreviewSource()
        }

        assertSame(holder.instance, holder.instance)

        assertEquals(1, created)
    }

    private class FakePreviewSource : MediaPreviewSource {
        override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(image = null, durationMillis = null)
    }
}
