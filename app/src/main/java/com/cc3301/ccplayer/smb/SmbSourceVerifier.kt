package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkSourceVerifier

/** SMB 连通性校验：试连一次共享就断开；用独立的传输，不碰应用级会话与共享传输。 */
class SmbSourceVerifier(private val connector: SmbConnector) : NetworkSourceVerifier {

    override suspend fun verify(url: String, username: String, password: String) {
        val location = SmbLocation.parse(url)
            ?: throw MediaSourceException(MediaFailure.NotFound, "网络源地址不合法：$url")
        if (location.share.isEmpty()) {
            connector.shares(location.host, location.port, username, password)
            return
        }
        val transport = connector.connectTransport(location.host, location.port)
        try {
            transport.connectShare(location.share, username, password).close()
        } finally {
            transport.close()
        }
    }
}
