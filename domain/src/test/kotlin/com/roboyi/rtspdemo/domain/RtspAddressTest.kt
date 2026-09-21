package com.roboyi.rtspdemo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspAddressTest {
    @Test fun onlyUsableRtspEndpointsAreAccepted() {
        assertTrue(RtspAddress.isSupported("rtsp://user:password@camera.local:554/live"))
        assertTrue(RtspAddress.isSupported("rtsps://[::1]:8554/live"))
        assertFalse(RtspAddress.isSupported("rtsp://"))
        assertFalse(RtspAddress.isSupported("https://camera.local/live"))
        assertFalse(RtspAddress.isSupported("rtsp://camera.local/live\nInjected"))
    }

    @Test fun historyNeverDisplaysCredentialsOrStreamTokens() {
        assertEquals("rtsp://camera.local:554", RtspAddress.maskCredentials(
            "rtsp://user:password@camera.local:554/private/token?access_key=secret"))
        assertEquals("RTSP", RtspAddress.maskCredentials("malformed"))
    }
}
