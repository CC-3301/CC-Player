package com.cc3301.ccplayer.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CrashLoggerTest {

    private val error = IllegalStateException("boom")

    @Test
    fun `同步写日志后交回默认处理器`() {
        var written: Throwable? = null
        var delegated: Throwable? = null
        val logger = CrashLogger({ true }, { written = it })
        val previous = Thread.UncaughtExceptionHandler { _, throwable -> delegated = throwable }

        logger.handle(Thread.currentThread(), error, previous)

        assertSame(error, written)
        assertSame(error, delegated)
    }

    @Test
    fun `格式化日志抛异常仍交回默认处理器`() {
        var delegated: Throwable? = null
        val logger = CrashLogger({ true }, { throw IllegalStateException("格式化失败") })
        val previous = Thread.UncaughtExceptionHandler { _, throwable -> delegated = throwable }

        logger.handle(Thread.currentThread(), error, previous)

        assertSame(error, delegated)
    }

    @Test
    fun `关闭时不写日志但仍交回默认处理器`() {
        var written = false
        var delegated: Throwable? = null
        val logger = CrashLogger({ false }, { written = true })
        val previous = Thread.UncaughtExceptionHandler { _, throwable -> delegated = throwable }

        logger.handle(Thread.currentThread(), error, previous)

        assertFalse(written)
        assertSame(error, delegated)
    }

    @Test
    fun `读取开关抛异常仍交回默认处理器`() {
        var written = false
        var delegated: Throwable? = null
        val logger = CrashLogger({ throw IllegalStateException("读不了设置") }, { written = true })
        val previous = Thread.UncaughtExceptionHandler { _, throwable -> delegated = throwable }

        logger.handle(Thread.currentThread(), error, previous)

        assertFalse(written)
        assertSame(error, delegated)
    }

    @Test
    fun `没有默认处理器时重抛原异常`() {
        val logger = CrashLogger({ true }, { })

        val thrown = assertThrows(IllegalStateException::class.java) {
            logger.handle(Thread.currentThread(), error, null)
        }

        assertSame(error, thrown)
    }
}
