package com.cc3301.ccplayer.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFailureCauseTest {

    @Test
    fun `没有原因时不追加`() {
        assertNull(RuntimeException("失败").causeClassNameOrNull())
    }

    @Test
    fun `取异常链最内层的原因类型`() {
        val deepest = IllegalStateException("底层")
        val wrapped = RuntimeException("包装", deepest)

        assertEquals("IllegalStateException", wrapped.causeClassNameOrNull())
    }

    @Test
    fun `原因互相指向时不死循环`() {
        val first = RuntimeException("甲")
        val second = IllegalStateException("乙", first)
        first.initCause(second)

        assertEquals("环链停在已到的最内层", "IllegalStateException", first.causeClassNameOrNull())
    }

    @Test
    fun `超过最大层数时停在上限处`() {
        var chain: Throwable = IOException("最深")
        repeat(2) { chain = RuntimeException("层", chain) }
        chain = IllegalStateException("距顶部 8 层", chain)
        repeat(8) { chain = RuntimeException("层", chain) }

        assertEquals("IllegalStateException", chain.causeClassNameOrNull())
    }
}
