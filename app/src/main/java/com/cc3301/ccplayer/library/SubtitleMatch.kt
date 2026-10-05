package com.cc3301.ccplayer.library

/** 外挂字幕的扩展名。 */
private val subtitleExtensions = setOf("srt", "ass")

/**
 * 语言后缀：`ep01.chs.ass` 的 `chs`、`ep01.zh-CN.ass` 的 `zh-CN`。
 * 后缀表之外的名字不按外挂字幕入选。
 */
private val languageSuffixes = setOf(
    "chs", "cht", "chi", "zho", "zh", "zh-cn", "zh-tw", "zh-hans", "zh-hant", "sc", "tc",
    "eng", "en", "jpn", "jp", "ja", "kor", "kr", "ko",
    "fre", "fra", "fr", "ger", "deu", "de", "spa", "es", "ita", "it",
    "rus", "ru", "por", "pt", "tha", "th", "vie", "vi", "ara", "ar", "hin", "hi",
)

/** 与媒体同名的字幕，得分高于带语言后缀的。 */
private const val EXACT_NAME_SCORE = 2

/** 媒体名加一个语言后缀的字幕。 */
private const val LANGUAGE_SUFFIX_SCORE = 1

/** 媒体名与语言后缀之间的分隔符。 */
private const val LANGUAGE_SUFFIX_SEPARATOR = '.'

/**
 * 同目录条目里与 [mediaName] 匹配的外挂字幕文件，按得分从高到低；同分按名称自然排序。
 *
 * 提取：条目名去扩展名后等于媒体名（去扩展名），或等于「媒体名.语言后缀」。目录条目、非 srt/ass 扩展名、
 * 语言后缀表之外的名字都不入选。
 */
fun matchExternalSubtitles(mediaName: String, entries: List<MediaFile>): List<MediaFile> = entries
    .filterNot { it.isDirectory }
    .mapNotNull { entry -> subtitleMatchScore(mediaName, entry.name)?.let { entry to it } }
    .sortedWith(
        compareByDescending<Pair<MediaFile, Int>> { it.second }
            .thenComparator { left, right -> compareNatural(left.first.name, right.first.name) },
    )
    .map { it.first }

/** 单条条目的匹配得分；不是外挂字幕或不与 [mediaName] 同前缀时返回 null。 */
fun subtitleMatchScore(mediaName: String, subtitleName: String): Int? {
    val extension = subtitleName.substringAfterLast('.', "")
    if (extension.lowercase() !in subtitleExtensions) return null
    val mediaBase = mediaName.substringBeforeLast('.')
    val subtitleStem = subtitleName.substringBeforeLast('.')
    if (mediaBase.isEmpty() || subtitleStem.isEmpty()) return null
    if (subtitleStem.equals(mediaBase, ignoreCase = true)) return EXACT_NAME_SCORE
    if (!subtitleStem.regionMatches(0, mediaBase, 0, mediaBase.length, ignoreCase = true)) return null
    if (subtitleStem.length <= mediaBase.length + 1) return null
    if (subtitleStem[mediaBase.length] != LANGUAGE_SUFFIX_SEPARATOR) return null
    val suffix = subtitleStem.substring(mediaBase.length + 1)
    return if (suffix.lowercase() in languageSuffixes) LANGUAGE_SUFFIX_SCORE else null
}
