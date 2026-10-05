package com.cc3301.ccplayer.webdav

/**
 * 一次 WebDAV 访问的参数：源地址、源内路径与凭据。
 *
 * 两个部分拼出的字符串即 [com.cc3301.ccplayer.library.MediaFile.location] 的取值。
 */
data class WebDavTarget(
    /** 书签地址，也是源根，不带末尾斜杠。 */
    val root: String,
    /** 源内路径，以 `/` 分隔；源根为空串。 */
    val path: String,
    val username: String,
    val password: String?,
) {

    /** 本参数指向的位置。 */
    val url: String get() = if (path.isEmpty()) root else "$root/$path"

    /** 本参数指向的条目名：源根用地址（去掉方案），否则是路径最后一段。 */
    val name: String get() = path.takeIf { it.isNotEmpty() }?.substringAfterLast('/') ?: root.substringAfter(SCHEME_SEPARATOR, root)

    /** 指向本参数下 [name] 子项的参数。 */
    fun child(name: String): WebDavTarget = copy(path = childPath(name))

    /** 子项在源内的路径。 */
    fun childPath(name: String): String = if (path.isEmpty()) name else "$path/$name"

    private companion object {
        const val SCHEME_SEPARATOR = "://"
    }
}
