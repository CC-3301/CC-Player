package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkSourceVerifier

/** WebDAV 连通性校验：对源地址发一次 PROPFIND。 */
class WebDavSourceVerifier(private val transport: WebDavTransport) : NetworkSourceVerifier {

    override suspend fun verify(url: String, username: String, password: String) {
        val address = WebDavAddress.normalize(url)
            ?: throw MediaSourceException(MediaFailure.NotFound, "网络源地址不合法：$url")
        transport.list(address, username, password)
    }
}
