package com.cc3301.ccplayer.network

/** 新增网络源前的连通性校验；校验不过抛出来源失败异常。 */
fun interface NetworkSourceVerifier {

    /** 用给定凭据试连 [url]；连不上就抛出带原因的异常。 */
    suspend fun verify(url: String, username: String, password: String)
}
