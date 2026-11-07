package com.cc3301.ccplayer.library

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 抽取失败按阶段分类：打开数据源 / 读取 / 解码 / 超时。 */
class PreviewFailureStageTest {

    @Test
    fun `打开数据源失败记打开数据源`() {
        val failure = PreviewStageException(PreviewFailureStage.OpenSource, "打开失败")

        assertEquals("打开数据源", previewFailureStage(failure))
    }

    @Test
    fun `读取失败记读取`() {
        val failure = PreviewStageException(PreviewFailureStage.Read, "读取失败")

        assertEquals("读取", previewFailureStage(failure))
    }

    @Test
    fun `解码失败记解码`() {
        val failure = PreviewStageException(PreviewFailureStage.Decode, "解码失败")

        assertEquals("解码", previewFailureStage(failure))
    }

    @Test
    fun `超时记超时`() {
        val failure = PreviewStageException(PreviewFailureStage.Timeout, "超时")

        assertEquals("超时", previewFailureStage(failure))
    }

    @Test
    fun `文件不存在按打开数据源记`() {
        assertEquals("打开数据源", previewFailureStage(FileNotFoundException("没有这个文件")))
    }

    @Test
    fun `位置解析失败记路径解析`() {
        assertEquals("路径解析", previewFailureStage(IllegalArgumentException("坏位置")))
        assertEquals("路径解析", previewFailureStage(SecurityException("没有权限")))
    }

    @Test
    fun `其它读取异常归到读取`() {
        assertEquals("读取", previewFailureStage(IOException("读不出来")))
    }
}
