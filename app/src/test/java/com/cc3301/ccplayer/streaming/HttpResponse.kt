package com.cc3301.ccplayer.streaming

import java.net.InetAddress
import java.net.Socket
import java.net.URI

/** 测试用的一次 HTTP 响应。 */
class HttpResponse(val statusCode: Int, private val headers: Map<String, String>, val body: ByteArray) {

    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        fun parse(raw: ByteArray): HttpResponse {
            val separator = headerEnd(raw)
            val head = String(raw, 0, if (separator < 0) raw.size else separator, Charsets.ISO_8859_1)
            val body = if (separator < 0) ByteArray(0) else raw.copyOfRange(separator + 4, raw.size)
            val lines = head.split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            return HttpResponse(lines.first().split(' ')[1].toInt(), headers, body)
        }

        private fun headerEnd(raw: ByteArray): Int {
            for (index in 0..raw.size - 4) {
                if (raw[index] == 13.toByte() && raw[index + 1] == 10.toByte() &&
                    raw[index + 2] == 13.toByte() && raw[index + 3] == 10.toByte()
                ) {
                    return index
                }
            }
            return -1
        }
    }
}

/** 用裸 socket 发一次请求并读回完整响应；服务器发完即关连接。 */
fun httpRequest(url: String, method: String = "GET", range: String? = null): HttpResponse {
    val uri = URI(url)
    Socket(InetAddress.getByName(uri.host), uri.port).use { socket ->
        val head = buildString {
            append("$method ${uri.path} HTTP/1.1\r\n")
            append("Host: ${uri.host}\r\n")
            if (range != null) append("Range: $range\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            flush()
        }
        return HttpResponse.parse(socket.getInputStream().readBytes())
    }
}
