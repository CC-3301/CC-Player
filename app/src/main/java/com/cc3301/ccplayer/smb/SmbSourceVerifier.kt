package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkSourceVerifier

/** SMB 连通性校验：试连一次共享就断开。 */
class SmbSourceVerifier(private val connector: SmbConnector) : NetworkSourceVerifier {

    override suspend fun verify(url: String, username: String, password: String) {
        val location = SmbLocation.parse(url)
            ?: throw MediaSourceException(MediaFailure.NotFound, "网络源地址不合法：$url")
        if (location.share.isEmpty()) {
            connector.shares(
                host = location.host,
                port = location.port,
                username = username,
                password = password,
            )
        } else {
            connector.connect(
                host = location.host,
                port = location.port,
                share = location.share,
                username = username,
                password = password,
            ).close()
        }
    }
}
