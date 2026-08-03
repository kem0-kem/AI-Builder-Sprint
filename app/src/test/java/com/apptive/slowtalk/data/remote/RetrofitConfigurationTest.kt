package com.apptive.slowtalk.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrofitConfigurationTest {
    @Test
    fun `base URL gets one trailing slash`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            normalizeBaseUrl("http://10.0.2.2:8000/api/v1"),
        )
    }

    @Test
    fun `base URL keeps a single trailing slash`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            normalizeBaseUrl("  http://10.0.2.2:8000/api/v1///  "),
        )
    }

    @Test
    fun `blank configuration uses the emulator API URL`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/",
            configuredBaseUrl(""),
        )
    }

    @Test
    fun `websocket base URL uses the normalized API base path`() {
        assertEquals(
            "ws://10.0.2.2:8000/api/v1/",
            webSocketBaseUrl("http://10.0.2.2:8000/api/v1"),
        )
    }
}
