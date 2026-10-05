package com.cc3301.ccplayer.library

/** 列表排序选项。 */
enum class SortOrder {
    NameAscending,
    NameDescending,
    LastModifiedNewestFirst,
    LastModifiedOldestFirst,
}

private val byNaturalName = Comparator<MediaFile> { left, right -> compareNatural(left.name, right.name) }

/**
 * 列表展示顺序：子目录恒在文件之前，排序方向只作用于组内次序。
 * 按修改时间排序时，修改时间相同按名称自然排序兜底。
 */
fun List<MediaFile>.sortedForDisplay(order: SortOrder): List<MediaFile> = sortedWith(
    Comparator { left, right ->
        if (left.isDirectory != right.isDirectory) {
            if (left.isDirectory) -1 else 1
        } else {
            val byKey = when (order) {
                SortOrder.NameAscending -> byNaturalName.compare(left, right)
                SortOrder.NameDescending -> byNaturalName.compare(right, left)
                SortOrder.LastModifiedNewestFirst -> right.lastModified.compareTo(left.lastModified)
                SortOrder.LastModifiedOldestFirst -> left.lastModified.compareTo(right.lastModified)
            }
            if (byKey != 0) byKey else byNaturalName.compare(left, right)
        }
    },
)
