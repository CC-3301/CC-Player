package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingNetworkSourceVerifierTest {

    private val smbAttempts = mutableListOf<Pair<String, String>>()
    private val webDavAttempts = mutableListOf<Pair<String, String>>()
    private val verifier = RoutingNetworkSourceVerifier(
        mapOf(
            "smb" to NetworkSourceVerifier { url, username, _ -> smbAttempts += url to username },
            "https" to NetworkSourceVerifier { url, username, _ -> webDavAttempts += url to username },
        ),
    )

    @Test
    fun `校验按方案分派，方案大小写不影响分派`() = runTest {
        verifier.verify(url = "smb://nas/media", username = "cc", password = "secret")
        verifier.verify(url = "HTTPS://dav.example.com/dav", username = "cc", password = "secret")

        assertEquals(listOf("smb://nas/media" to "cc"), smbAttempts)
        assertEquals(listOf("HTTPS://dav.example.com/dav" to "cc"), webDavAttempts)
    }

    @Test
    fun `未知方案校验给出找不到原因`() = runTest {
        val exception = runCatching { verifier.verify(url = "ftp://nas/media", username = "cc", password = "secret") }
            .exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }
}
