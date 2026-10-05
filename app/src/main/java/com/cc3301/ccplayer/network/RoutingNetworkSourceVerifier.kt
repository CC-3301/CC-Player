package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException

/** 网络源地址按方案分派给各协议的连通性校验。 */
class RoutingNetworkSourceVerifier(private val verifiersByScheme: Map<String, NetworkSourceVerifier>) :
    NetworkSourceVerifier {

    override suspend fun verify(url: String, username: String, password: String) {
        val verifier = verifiersByScheme[url.substringBefore(SCHEME_SEPARATOR, missingDelimiterValue = "").lowercase()]
            ?: throw MediaSourceException(MediaFailure.NotFound, "不支持的网络源地址：$url")
        verifier.verify(url, username, password)
    }

    private companion object {
        const val SCHEME_SEPARATOR = "://"
    }
}
