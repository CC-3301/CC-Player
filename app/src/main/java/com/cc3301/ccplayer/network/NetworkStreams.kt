package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.schemeOf
import java.io.Closeable

/**
 * 网络媒体播放接缝：把网络位置映到本机回环地址，播放核心只拿到本机地址。
 *
 * 各协议各自实现，与各自的文件树一一对应。
 */
interface NetworkStreams : Closeable {

    /** 起播前试连一次；连不上或位置不属于任何书签时抛出来源失败异常。 */
    suspend fun probe(location: String)

    /** 位置对应的本机地址。 */
    fun urlFor(location: String): String
}

/**
 * 位置按方案分派给各自的回环代理，分派规则与 [com.cc3301.ccplayer.library.RoutingMediaFileTree] 相同。
 */
class RoutingNetworkStreams(private val streamsByScheme: Map<String, NetworkStreams>) : NetworkStreams {

    override suspend fun probe(location: String) {
        streamsFor(location).probe(location)
    }

    override fun urlFor(location: String): String = streamsFor(location).urlFor(location)

    /** 同一个代理登记在多个方案下时只关一次。 */
    override fun close() {
        streamsByScheme.values.toSet().forEach { streams -> streams.close() }
    }

    private fun streamsFor(location: String): NetworkStreams =
        streamsByScheme[schemeOf(location)]
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有能处理这个位置的网络源：$location")
}
