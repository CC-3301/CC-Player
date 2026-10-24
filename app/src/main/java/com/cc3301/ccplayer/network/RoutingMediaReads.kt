package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.library.schemeOf
import com.cc3301.ccplayer.streaming.ByteSource

/** 预览读取按方案分派给各协议的随机读实现，分派规则与 [RoutingNetworkStreams] 相同。 */
class RoutingMediaReads(private val readsByScheme: Map<String, NetworkMediaReads>) : NetworkMediaReads {

    override suspend fun openRead(location: String): ByteSource =
        readsByScheme[schemeOf(location)]?.openRead(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有能处理这个位置的网络源：$location")

    override suspend fun sourceKey(location: String): String =
        readsByScheme[schemeOf(location)]?.sourceKey(location) ?: location
}
