package com.cc3301.ccplayer.library

/**
 * Windows `StrCmpLogicalW` 等价的自然排序：数字段按数值比较，字母不区分大小写。
 *
 * 结果是全序：数字段数值相同时（`ep2` / `ep02`）前导零少的在前，整串只差大小写时按原始码点序兜底。
 */
fun compareNatural(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        if (left[leftIndex].isAsciiDigit() && right[rightIndex].isAsciiDigit()) {
            val leftEnd = digitRunEnd(left, leftIndex)
            val rightEnd = digitRunEnd(right, rightIndex)
            val comparison = compareDigitRuns(left, leftIndex, leftEnd, right, rightIndex, rightEnd)
            if (comparison != 0) return comparison
            leftIndex = leftEnd
            rightIndex = rightEnd
            continue
        }
        val comparison = left[leftIndex].lowercaseChar().compareTo(right[rightIndex].lowercaseChar())
        if (comparison != 0) return comparison
        leftIndex++
        rightIndex++
    }
    if (leftIndex < left.length) return 1
    if (rightIndex < right.length) return -1
    return left.compareTo(right)
}

/** 数字段按数值比较：去掉前导零后先比位数，位数相同再逐位比；数值相同时前导零少的在前。 */
private fun compareDigitRuns(
    left: String,
    leftStart: Int,
    leftEnd: Int,
    right: String,
    rightStart: Int,
    rightEnd: Int,
): Int {
    val leftZeros = leadingZeroCount(left, leftStart, leftEnd)
    val rightZeros = leadingZeroCount(right, rightStart, rightEnd)
    val leftDigits = leftStart + leftZeros
    val rightDigits = rightStart + rightZeros
    val leftLength = leftEnd - leftDigits
    val rightLength = rightEnd - rightDigits
    if (leftLength != rightLength) return leftLength.compareTo(rightLength)
    for (offset in 0 until leftLength) {
        val comparison = left[leftDigits + offset].compareTo(right[rightDigits + offset])
        if (comparison != 0) return comparison
    }
    return leftZeros.compareTo(rightZeros)
}

private fun digitRunEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && text[end].isAsciiDigit()) end++
    return end
}

private fun leadingZeroCount(text: String, start: Int, end: Int): Int {
    var index = start
    while (index < end && text[index] == '0') index++
    return index - start
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
