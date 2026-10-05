package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun `数字段按数值比较，2 排在 10 之前`() {
        assertOrder("2", "10", -1)
        assertOrder("2.mkv", "10.mkv", -1)
    }

    @Test
    fun `混合字母数字段按段内数值比较`() {
        assertOrder("ep1.mkv", "ep10.mkv", -1)
        assertOrder("ep2.mkv", "ep10.mkv", -1)
        assertOrder("ep1.mkv", "ep02.mkv", -1)
        assertOrder("ep02.mkv", "ep3.mkv", -1)
        assertOrder("第1集.mkv", "第10集.mkv", -1)
        assertOrder("第2季第10集.mkv", "第2季第2集.mkv", 1)
    }

    @Test
    fun `数字段数值相同时前导零少的在前`() {
        assertOrder("ep2.mkv", "ep02.mkv", -1)
        assertOrder("007.mkv", "7.mkv", 1)
        assertOrder("0.mkv", "00.mkv", -1)
        assertOrder("第03集.mkv", "第3集.mkv", 1)
    }

    @Test
    fun `纯数字文件名按数值排序`() {
        assertOrder("1", "2", -1)
        assertOrder("9", "10", -1)
        assertOrder("100", "99", 1)
        assertOrder("0", "1", -1)
    }

    @Test
    fun `字母不区分大小写`() {
        assertOrder("abc.mkv", "abd.mkv", -1)
        assertOrder("B.mkv", "a.mkv", 1)
        assertOrder("Ep3.mkv", "ep03.mkv", -1)
        assertOrder("第1集.MKV", "第2集.mkv", -1)
    }

    @Test
    fun `只差大小写的名称按原始码点序排出确定次序`() {
        assertOrder("ABC.mkv", "abc.mkv", -1)
        assertOrder("a.mkv", "A.mkv", 1)
    }

    @Test
    fun `一方是另一方的前缀时短的在前`() {
        assertOrder("abc", "abcd", -1)
        assertOrder("ep1", "ep1.mkv", -1)
    }

    @Test
    fun `相同的名称相等`() {
        assertOrder("ep2.mkv", "ep2.mkv", 0)
    }

    @Test
    fun `空名称与普通名称可比较`() {
        assertOrder("", "a", -1)
        assertOrder("", "", 0)
    }

    @Test
    fun `中文与特殊字符不崩且次序确定`() {
        assertOrder("下载.mkv", "未命名.mkv", -1)
        assertOrder("a b#1@.mkv", "a b#2@.mkv", -1)
        assertOrder("[组]第3集.mkv", "第10集.mkv", -1)
    }

    @Test
    fun `排序结果与输入顺序无关`() {
        val names = listOf("ep10.mkv", "ep2.mkv", "EP2.MKV", "ep02.mkv", "2.mkv", "10.mkv", "第1集.mkv", ".hidden")
        val comparator = Comparator<String> { left, right -> compareNatural(left, right) }

        assertEquals(names.sortedWith(comparator), names.reversed().sortedWith(comparator))
    }

    private fun assertOrder(left: String, right: String, expected: Int) {
        assertEquals("compareNatural($left, $right)", expected, Integer.signum(compareNatural(left, right)))
        assertEquals("compareNatural($right, $left)", -expected, Integer.signum(compareNatural(right, left)))
    }
}
