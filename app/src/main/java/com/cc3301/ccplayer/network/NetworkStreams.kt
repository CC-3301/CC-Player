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

    /** 登记一次准备，返回这次准备的所有权凭证；取消打开时用它只丢弃本次准备的句柄。 */
    fun prepare(location: String): PreparedSource

    /** 位置对应的本机地址。 */
    fun urlFor(location: String): String

    /** 声明此刻仍需保留的媒体位置：不在集合里的已就绪源当场摘走，在关源线程关闭；地址保留。 */
    fun retain(locations: Set<String>)

    /** 当前目标不再需要该源：当场摘走已就绪的那一条，在后台关闭；地址保留，下一次请求重新建立。 */
    fun returnSource(location: String)

    /** 丢弃已建立的字节源；之后的请求重新建立。退到后台后底层连接可能已失效。 */
    fun discardByteSources()
}

/**
 * 一次探测交接的所有权凭证：由发起方在探测前登记，作废时只丢弃本次准备的句柄。
 * 已经预置的句柄丢掉；还在途的打开回来时自己关掉；槽里换成别人那次准备的句柄就不动它。
 */
interface PreparedSource {

    /** 在这次准备名下试连一次；连不上或位置不属于任何书签时抛出来源失败异常。 */
    suspend fun probe(location: String)

    /** 本次准备作废。 */
    fun discard()
}

/**
 * 位置按方案分派给各自的回环代理，分派规则与 [com.cc3301.ccplayer.library.RoutingMediaFileTree] 相同。
 */
class RoutingNetworkStreams(private val streamsByScheme: Map<String, NetworkStreams>) : NetworkStreams {

    /** 上一版保留集合：只处理被移出集合的位置。 */
    private val retention = RetainedLocations()

    override fun prepare(location: String): PreparedSource = streamsFor(location).prepare(location)

    override fun urlFor(location: String): String = streamsFor(location).urlFor(location)

    /** 差集与按协议分派都在这里：只把被移出集合的位置交给对应协议归还。 */
    override fun retain(locations: Set<String>) {
        retention.droppedBy(locations).forEach { location -> streamsFor(location).returnSource(location) }
    }

    override fun returnSource(location: String) {
        streamsFor(location).returnSource(location)
    }

    /** 同一个代理登记在多个方案下时只丢弃一次。 */
    override fun discardByteSources() {
        streamsByScheme.values.toSet().forEach { streams -> streams.discardByteSources() }
    }

    /** 同一个代理登记在多个方案下时只关一次。 */
    override fun close() {
        streamsByScheme.values.toSet().forEach { streams -> streams.close() }
    }

    private fun streamsFor(location: String): NetworkStreams =
        streamsByScheme[schemeOf(location)]
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有能处理这个位置的网络源：$location")
}

/**
 * 保留集合的差集：记住上一版集合，只交出被移出集合的位置；集合未变化时交出空集。
 * 一个实例代表一处归还范围：路由层一处，各协议各一处。
 */
internal class RetainedLocations {

    private var retained: Set<String> = emptySet()

    fun droppedBy(locations: Set<String>): Set<String> {
        if (locations == retained) return emptySet()
        val dropped = retained - locations
        retained = locations
        return dropped
    }
}
