package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 分离 Surface 的下发顺序：属性必须先于摘挂载，否则摘的时候输出还开着。 */
class SurfaceDetachOrderTest {

    @Test
    fun `分离先关输出再撤窗口，最后摘挂载`() {
        val calls = mutableListOf<String>()

        detachVideoOutput(
            setProperty = { name, value -> calls += "property:$name=$value" },
            setOption = { name, value -> calls += "option:$name=$value" },
            detachSurface = { calls += "detach" },
        )

        assertEquals(
            listOf("property:vo=null", "option:force-window=no", "detach"),
            calls,
        )
    }
}
